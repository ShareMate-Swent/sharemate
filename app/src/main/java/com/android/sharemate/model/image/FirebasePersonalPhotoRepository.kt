// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.item.ItemFields
import com.android.sharemate.model.item.ItemImageFields
import com.android.sharemate.model.item.ItemImageOrigin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageTask
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebasePersonalPhotoRepository
private constructor(
    storageProvider: () -> FirebaseStorage,
    firestoreProvider: () -> FirebaseFirestore,
) : PersonalPhotoRepository {
  constructor(
      storage: FirebaseStorage,
      firestore: FirebaseFirestore,
  ) : this({ storage }, { firestore })

  private val storage by lazy(storageProvider)
  private val firestore by lazy(firestoreProvider)

  override suspend fun upload(itemId: String, source: File): Result<PersonalPhotoReference> =
      outcome {
        require(itemId.isNotBlank() && '/' !in itemId && itemId != "." && itemId != "..")
        val bytes =
            withContext(Dispatchers.IO) {
              val context = currentCoroutineContext()
              source.inputStream().use { PhotoBytes.readBounded(it) { context.ensureActive() } }
            }
        val contentType = PhotoBytes.contentType(bytes)
        val ownerId =
            requireNotNull(
                firestore
                    .collection(FirestoreCollections.ITEMS)
                    .document(itemId)
                    .get(Source.SERVER)
                    .await()
                    .getString(ItemFields.OWNER_ID)) {
                  "Item has no owner"
                }
        require(ownerId.isNotBlank())
        val metadata =
            StorageMetadata.Builder()
                .setContentType(contentType)
                .setCustomMetadata(ITEM_OWNER_ID_METADATA, ownerId)
                .build()
        val reference = PersonalPhotoReference.forItem(itemId, UUID.randomUUID())
        currentCoroutineContext().ensureActive()
        storage.reference.child(reference.path).putBytes(bytes, metadata).awaitPhotoTransfer()
        reference
      }

  override suspend fun load(reference: PersonalPhotoReference): Result<ByteArray> = outcome {
    val context = currentCoroutineContext()
    var bytes: ByteArray? = null
    context.ensureActive()
    // The SDK runs the processor off the main thread; we own closing its stream.
    storage.reference
        .child(reference.path)
        .getStream { _, stream ->
          stream.use { bytes = PhotoBytes.readBounded(it) { context.ensureActive() } }
        }
        .awaitPhotoTransfer()
    checkNotNull(bytes).also { PhotoBytes.contentType(it) }
  }

  override suspend fun delete(reference: PersonalPhotoReference): Result<Unit> = outcome {
    currentCoroutineContext().ensureActive()
    // Unlike UploadTask/StreamDownloadTask, this SDK Task has no cancel operation.
    storage.reference.child(reference.path).delete().await()
    Unit
  }

  override suspend fun deleteAttached(itemId: String): Result<Unit> = outcome {
    val item =
        firestore.collection(FirestoreCollections.ITEMS).document(itemId).get(Source.SERVER).await()
    if (item.getString(ITEM_IMAGE_ORIGIN_PATH) == ItemImageOrigin.PERSONAL_PHOTO.name) {
      val path = item.getString(ITEM_IMAGE_REFERENCE_PATH)
      if (path != null && PersonalPhotoReference.hasItemPrefix(path, itemId)) {
        val error = delete(PersonalPhotoReference(path)).exceptionOrNull()
        // A previous attempt may have deleted the blob before the item deletion failed.
        if (error != null &&
            (error as? StorageException)?.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND)
            throw error
      }
    }
  }

  private suspend fun <T> outcome(block: suspend () -> T): Result<T> =
      try {
        currentCoroutineContext().ensureActive()
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
      }

  companion object {
    private const val ITEM_OWNER_ID_METADATA = "itemOwnerId"
    private const val ITEM_IMAGE_ORIGIN_PATH = "${ItemFields.IMAGE}.${ItemImageFields.ORIGIN}"
    private const val ITEM_IMAGE_REFERENCE_PATH = "${ItemFields.IMAGE}.${ItemImageFields.REFERENCE}"

    fun create(): PersonalPhotoRepository =
        FirebasePersonalPhotoRepository(
            { FirebaseStorage.getInstance() }, { FirebaseFirestore.getInstance() })
  }
}

/** Task.await alone cancels the wait, not the underlying Firebase Storage transfer. */
internal suspend fun <T : StorageTask<T>.SnapshotBase> StorageTask<T>.awaitPhotoTransfer(): T =
    try {
      await()
    } catch (cancelled: CancellationException) {
      cancel()
      throw cancelled
    }
