// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.Uri
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.*
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Stores photos through authenticated Storage requests, never public download-token URLs. */
class FirebaseReceiptCloudRepository(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReceiptCloudRepository {
  private val codec = AndroidInventorySubmissionStore(context, scheduleSync = false)

  override fun observeDeleted(ownerId: String, householdId: String): Flow<Set<String>> =
      watch(
              db.collection(ReceiptCloudPaths.USERS)
                  .document(ownerId)
                  .collection(ReceiptCloudPaths.DELETIONS)
                  .whereEqualTo(InventorySubmissionFields.HOUSEHOLD, householdId))
          .map { docs -> docs.map { it.id }.toSet() }

  /** Uploads one durable local snapshot. A deletion tombstone prevents stale resurrection. */
  suspend fun upload(entry: ReceiptCloudEntry) {
    val receipt = entry.submission
    val details = entry.details
    if (!receipt.draft && receipt.expenseProgress != ExpenseProgress.CONFIRMED) return
    val photoPath = ReceiptCloudPaths.photo(receipt.householdId, receipt.ownerId, receipt.id)
    val ref =
        db.collection(ReceiptCloudPaths.USERS)
            .document(receipt.ownerId)
            .collection(ReceiptCloudPaths.BACKUPS)
            .document(receipt.id)
    val payload =
        mapOf(
            InventorySubmissionFields.OWNER to receipt.ownerId,
            InventorySubmissionFields.HOUSEHOLD to receipt.householdId,
            InventorySubmissionFields.DRAFT to receipt.draft,
            ReceiptCloudPaths.SUBMISSION to codec.encode(receipt).toString(),
            ReceiptCloudPaths.TITLE to details.title,
            ReceiptCloudPaths.REVISION to details.revision,
            ReceiptCloudPaths.SCANNED_AT to details.scannedAtMillis,
            ReceiptCloudPaths.PAID_BY to details.draftPaidBy,
            ReceiptCloudPaths.PARTICIPANTS to details.draftParticipants.toList(),
            ReceiptCloudPaths.PHOTO_PATH to photoPath,
            ReceiptCloudPaths.PHOTO_READY to false)
    db.runTransaction { tx ->
          val existing = tx.get(ref)
          // Saved content is updated only by correctReceipt's atomic ledger transaction.
          if (!existing.exists() || existing.getBoolean(InventorySubmissionFields.DRAFT) == true)
              tx.set(ref, payload)
        }
        .await()
    if (details.photoUri.startsWith("file:")) {
      storage.reference
          .child(photoPath)
          .putFile(
              Uri.parse(details.photoUri),
              StorageMetadata.Builder().setContentType("image/jpeg").build())
          .await()
      ref.update(ReceiptCloudPaths.PHOTO_READY, true).await()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observe(ownerId: String, householdId: String): Flow<List<ReceiptCloudEntry>> {
    val shared =
        db.collectionGroup(ReceiptCloudPaths.BACKUPS)
            .whereEqualTo(InventorySubmissionFields.HOUSEHOLD, householdId)
            .whereEqualTo(InventorySubmissionFields.DRAFT, false)
    val drafts =
        db.collectionGroup(ReceiptCloudPaths.BACKUPS)
            .whereEqualTo(InventorySubmissionFields.HOUSEHOLD, householdId)
            .whereEqualTo(InventorySubmissionFields.OWNER, ownerId)
            .whereEqualTo(InventorySubmissionFields.DRAFT, true)
    return combine(watch(shared), watch(drafts)) { saved, private -> saved + private }
        .flatMapLatest { docs ->
          channelFlow {
            val decoded =
                docs.mapNotNull { document ->
                  try {
                    decode(document, ownerId, householdId) to
                        (document.getBoolean(ReceiptCloudPaths.PHOTO_READY) == true)
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: Exception) {
                    android.util.Log.w("ReceiptSync", "Skipping invalid receipt backup")
                    null
                  }
                }
            var entries = decoded.map { it.first }
            send(entries)
            val updates = Mutex()
            // Downloads belong to this snapshot: cancellation or a newer snapshot stops retries.
            decoded.forEachIndexed { index, (entry, ready) ->
              if (ready && entry.details.photoUri.isEmpty())
                  launch {
                    var retryMillis = INITIAL_PHOTO_RETRY_MILLIS
                    while (true) {
                      var pending: File? = null
                      try {
                        val photo = photoFile(entry.submission.id)
                        pending = File.createTempFile("receipt-download-", ".tmp", photo.parentFile)
                        val receipt = entry.submission
                        val download =
                            storage.reference
                                .child(
                                    ReceiptCloudPaths.photo(
                                        receipt.householdId, receipt.ownerId, receipt.id))
                                .getFile(pending)
                        try {
                          download.await()
                        } catch (cancelled: CancellationException) {
                          download.cancel()
                          // Storage cancellation is asynchronous; clean up again once writing
                          // stops.
                          val temporary = pending
                          download.addOnCompleteListener({ it.run() }) { temporary.delete() }
                          throw cancelled
                        }
                        check(pending.length() > 0 && pending.renameTo(photo))
                        updates.withLock {
                          entries =
                              entries.toMutableList().also {
                                it[index] =
                                    entry.copy(
                                        details =
                                            entry.details.copy(
                                                photoUri = Uri.fromFile(photo).toString()))
                              }
                          send(entries)
                        }
                        break
                      } catch (cancelled: CancellationException) {
                        throw cancelled
                      } catch (failure: Exception) {
                        val classified = receiptCloudFailure(failure)
                        if (classified is ReceiptCloudPermanentException) throw classified
                        // Retry without requiring a new Firestore event, while observation is
                        // active.
                      } finally {
                        pending?.delete()
                      }
                      delay(retryMillis)
                      retryMillis = (retryMillis * 2).coerceAtMost(MAX_PHOTO_RETRY_MILLIS)
                    }
                  }
            }
          }
        }
        .flowOn(dispatcher)
  }

  private fun photoFile(id: String): File =
      File(File(context.filesDir, "receipts").also { it.mkdirs() }, "$id.jpg")

  private fun decode(
      document: DocumentSnapshot,
      ownerId: String,
      householdId: String
  ): ReceiptCloudEntry {
    val author = checkNotNull(document.getString(InventorySubmissionFields.OWNER))
    val submission =
        codec.decode(
            checkNotNull(document.getString(ReceiptCloudPaths.SUBMISSION)), author, householdId)
    require(submission.id == document.id && (!submission.draft || author == ownerId))
    val photo = photoFile(submission.id)
    return ReceiptCloudEntry(
        submission,
        ReceiptDetails(
            submission.id,
            author,
            householdId,
            if (photo.exists()) Uri.fromFile(photo).toString() else "",
            document.getLong(ReceiptCloudPaths.SCANNED_AT) ?: 0,
            if (submission.draft) null else document.getLong(ReceiptCloudPaths.SCANNED_AT),
            document.getString(ReceiptCloudPaths.TITLE).orEmpty(),
            document.getString(ReceiptCloudPaths.PAID_BY),
            (document.get(ReceiptCloudPaths.PARTICIPANTS) as? List<*>)
                ?.filterIsInstance<String>()
                ?.toSet()
                .orEmpty(),
            document.getLong(ReceiptCloudPaths.REVISION) ?: 0))
  }

  private companion object {
    const val INITIAL_PHOTO_RETRY_MILLIS = 1_000L
    const val MAX_PHOTO_RETRY_MILLIS = 300_000L
  }

  private fun watch(query: Query): Flow<List<DocumentSnapshot>> = callbackFlow {
    val registration =
        query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
          if (error != null) close(receiptCloudFailure(error))
          else if (snapshot != null &&
              !snapshot.metadata.isFromCache &&
              !snapshot.metadata.hasPendingWrites())
              trySend(snapshot.documents)
        }
    awaitClose { registration.remove() }
  }
}

/** Keeps Firebase error types inside the model layer. */
internal fun receiptCloudFailure(failure: Exception): Exception {
  val permanent =
      when (failure) {
        is FirebaseFirestoreException ->
            failure.code in
                setOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.UNAUTHENTICATED,
                    FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                    FirebaseFirestoreException.Code.NOT_FOUND,
                    FirebaseFirestoreException.Code.UNIMPLEMENTED)
        is StorageException ->
            failure.errorCode in
                setOf(
                    StorageException.ERROR_NOT_AUTHORIZED,
                    StorageException.ERROR_NOT_AUTHENTICATED,
                    StorageException.ERROR_OBJECT_NOT_FOUND,
                    StorageException.ERROR_BUCKET_NOT_FOUND,
                    StorageException.ERROR_PROJECT_NOT_FOUND)
        else -> false
      }
  return if (permanent) ReceiptCloudPermanentException(failure) else failure
}
