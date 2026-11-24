// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.Uri
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.*
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

/** Stores photos through authenticated Storage requests, never public download-token URLs. */
class FirebaseReceiptCloudRepository(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : ReceiptCloudRepository {
  private val lifecycle = ReceiptCacheLifecycle(context)
  private val codec = AndroidInventorySubmissionStore(context, scheduleSync = false)

  override fun observeDeleted(ownerId: String, householdId: String): Flow<Set<String>> =
      watch(
              db.collection("users")
                  .document(ownerId)
                  .collection("receiptDeletions")
                  .whereEqualTo("householdId", householdId))
          .map { docs -> docs.map { it.id }.toSet() }

  /** Uploads one durable local snapshot. A deletion tombstone prevents stale resurrection. */
  suspend fun upload(entry: ReceiptCloudEntry) {
    val receipt = entry.submission
    check(lifecycle.isCurrent(receipt.householdId))
    val details = entry.details
    if (!receipt.draft && receipt.expenseProgress != ExpenseProgress.CONFIRMED) return
    val photoPath = "receiptPhotos/${receipt.householdId}/${receipt.ownerId}/${receipt.id}.jpg"
    val ref = db.collection("receiptBackups").document(receipt.id)
    val payload =
        mapOf(
            "ownerId" to receipt.ownerId,
            "householdId" to receipt.householdId,
            "draft" to receipt.draft,
            "submission" to codec.encode(receipt).toString(),
            "title" to details.title,
            "scannedAtMillis" to details.scannedAtMillis,
            "draftPaidBy" to details.draftPaidBy,
            "draftParticipants" to details.draftParticipants.toList(),
            "photoPath" to photoPath,
            "photoReady" to false)
    db.runTransaction { tx ->
          val existing = tx.get(ref)
          // Saved content is updated only by correctReceipt's atomic ledger transaction.
          if (!existing.exists() || existing.getBoolean("draft") == true)
              tx.set(ref, payload + ("photoReady" to (existing.getBoolean("photoReady") == true)))
        }
        .await()
    if (details.photoUri.startsWith("file:")) {
      storage.reference
          .child(photoPath)
          .putFile(
              Uri.parse(details.photoUri),
              StorageMetadata.Builder().setContentType("image/jpeg").build())
          .await()
      ref.update("photoReady", true).await()
    }
  }

  override fun observe(ownerId: String, householdId: String): Flow<List<ReceiptCloudEntry>> {
    val shared =
        db.collection("receiptBackups")
            .whereEqualTo("householdId", householdId)
            .whereEqualTo("draft", false)
    val drafts =
        db.collection("receiptBackups")
            .whereEqualTo("householdId", householdId)
            .whereEqualTo("ownerId", ownerId)
            .whereEqualTo("draft", true)
    return combine(watch(shared), watch(drafts)) { saved, private -> saved + private }
        .map { docs ->
          docs.map { document ->
            val author = checkNotNull(document.getString("ownerId"))
            val submission =
                codec.decode(checkNotNull(document.getString("submission")), author, householdId)
            require(submission.id == document.id && (!submission.draft || author == ownerId))
            val directory = File(context.filesDir, "receipts").also { it.mkdirs() }
            val photo = File(directory, "${submission.id}.jpg")
            val path = "receiptPhotos/$householdId/$author/${submission.id}.jpg"
            if (!photo.exists() && document.getBoolean("photoReady") == true) {
              lifecycle.trackDownload(ownerId, householdId, submission.id)
              val pending = File.createTempFile("receipt-download-", ".tmp", directory)
              try {
                storage.reference.child(path).getFile(pending).await()
                lifecycle.write(householdId) {
                  check(pending.length() > 0 && pending.renameTo(photo))
                }
              } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                // The next cloud observation retries; never cache a partial image as complete.
              } finally {
                pending.delete()
              }
            }
            ReceiptCloudEntry(
                submission,
                ReceiptDetails(
                    submission.id,
                    author,
                    householdId,
                    if (photo.exists()) Uri.fromFile(photo).toString() else "",
                    document.getLong("scannedAtMillis") ?: 0,
                    if (submission.draft) null else document.getLong("scannedAtMillis"),
                    document.getString("title").orEmpty(),
                    document.getString("draftPaidBy"),
                    (document.get("draftParticipants") as? List<*>)
                        ?.filterIsInstance<String>()
                        ?.toSet()
                        .orEmpty()))
          }
        }
  }

  private fun watch(query: Query): Flow<List<DocumentSnapshot>> = callbackFlow {
    val registration =
        query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
          if (error != null) close(error)
          else if (snapshot != null &&
              !snapshot.metadata.isFromCache &&
              !snapshot.metadata.hasPendingWrites())
              trySend(snapshot.documents)
        }
    awaitClose { registration.remove() }
  }
}
