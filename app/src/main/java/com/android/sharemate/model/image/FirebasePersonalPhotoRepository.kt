// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.item.ItemImageOrigin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
              source.inputStream().use { readBounded(it) { context.ensureActive() } }
            }
        val contentType = photoType(bytes)
        val ownerId =
            requireNotNull(
                firestore
                    .collection(FirestoreCollections.ITEMS)
                    .document(itemId)
                    .get(Source.SERVER)
                    .await()
                    .getString("ownerId")) {
                  "Item has no owner"
                }
        require(ownerId.isNotBlank())
        val metadata =
            StorageMetadata.Builder()
                .setContentType(contentType)
                .setCustomMetadata("itemOwnerId", ownerId)
                .build()
        val reference = PersonalPhotoReference("itemPhotos/$itemId/${UUID.randomUUID()}")
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
          stream.use { bytes = readBounded(it) { context.ensureActive() } }
        }
        .awaitPhotoTransfer()
    checkNotNull(bytes).also { photoType(it) }
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
    if (item.getString("image.origin") == ItemImageOrigin.PERSONAL_PHOTO.name) {
      val path = item.getString("image.reference")
      if (path?.startsWith("itemPhotos/$itemId/") == true) {
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

  private fun readBounded(stream: InputStream, checkActive: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      checkActive()
      val count =
          stream.read(
              buffer, 0, minOf(buffer.size, PersonalPhotoRepository.MAX_BYTES + 1 - output.size()))
      if (count == -1) break
      require(output.size() + count <= PersonalPhotoRepository.MAX_BYTES) { "Photo exceeds 5 MiB" }
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }

  companion object {
    fun create(): PersonalPhotoRepository =
        FirebasePersonalPhotoRepository(
            { FirebaseStorage.getInstance() }, { FirebaseFirestore.getInstance() })
  }

  private fun photoType(bytes: ByteArray): String =
      when {
        bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() } -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "image/jpeg"
        else -> throw IllegalArgumentException("Expected a JPEG or PNG photo")
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
