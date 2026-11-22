// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.incident

import com.android.sharemate.model.image.PhotoBytes
import com.android.sharemate.model.image.awaitPhotoTransfer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Uses existing bounded photo readers and cancellable Storage transfers; no local cache is kept.
 */
class FirebaseIncidentPhotoRepository
internal constructor(
    storageProvider: () -> FirebaseStorage,
    private val auth: FirebaseAuth,
    private val newPhotoId: () -> UUID,
) : IncidentPhotoRepository {
  constructor(
      storage: FirebaseStorage,
      auth: FirebaseAuth
  ) : this({ storage }, auth, UUID::randomUUID)

  internal constructor(
      storage: FirebaseStorage,
      auth: FirebaseAuth,
      newPhotoId: () -> UUID,
  ) : this({ storage }, auth, newPhotoId)

  // Resolved on first use, so building the repository never needs a configured Storage bucket.
  private val storage: FirebaseStorage by lazy(storageProvider)

  override suspend fun upload(
      householdId: String,
      expectedAuthorId: String,
      source: File,
  ): Result<IncidentPhotoReference> =
      inSession(expectedAuthorId) { uid ->
        val reference = IncidentPhotoReference.forUpload(householdId, uid, newPhotoId())
        val bytes =
            withContext(Dispatchers.IO) {
              val context = currentCoroutineContext()
              source.inputStream().use { input ->
                PhotoBytes.readBounded(input) {
                  context.ensureActive()
                  checkSession(uid)
                }
              }
            }
        val metadata =
            StorageMetadata.Builder().setContentType(PhotoBytes.contentType(bytes)).build()
        currentCoroutineContext().ensureActive()
        checkSession(uid)
        storage.reference.child(reference.path).putBytes(bytes, metadata).awaitPhotoTransfer()
        reference
      }

  override suspend fun load(reference: IncidentPhotoReference): Result<ByteArray> =
      inSession(null) { uid ->
        val context = currentCoroutineContext()
        var bytes: ByteArray? = null
        // The SDK runs this processor off the main thread. We close and bound the stream ourselves.
        storage.reference
            .child(reference.path)
            .getStream { _, stream ->
              stream.use { input ->
                bytes =
                    PhotoBytes.readBounded(input) {
                      context.ensureActive()
                      checkSession(uid)
                    }
              }
            }
            .awaitPhotoTransfer()
        checkNotNull(bytes).also { PhotoBytes.contentType(it) }
      }

  override suspend fun exists(reference: IncidentPhotoReference): Result<Boolean> =
      inSession(null) {
        try {
          storage.reference.child(reference.path).metadata.await()
          true
        } catch (missing: StorageException) {
          if (missing.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw missing
          false
        }
      }

  private fun checkSession(uid: String) {
    check(auth.currentUser?.uid == uid) { SESSION_CHANGED }
  }

  private suspend fun <T> inSession(
      expectedUid: String?,
      action: suspend (String) -> T
  ): Result<T> =
      try {
        currentCoroutineContext().ensureActive()
        val uid = checkNotNull(auth.currentUser?.uid) { NO_SESSION }
        check(expectedUid == null || uid == expectedUid) { INVALID_AUTHOR }
        val value = action(uid)
        checkSession(uid)
        currentCoroutineContext().ensureActive()
        Result.success(value)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(failure)
      }

  companion object {
    /** Repository on the default Firebase app; Storage is only created when a photo is used. */
    fun create(): IncidentPhotoRepository =
        FirebaseIncidentPhotoRepository(
            { FirebaseStorage.getInstance() }, FirebaseAuth.getInstance(), UUID::randomUUID)

    private const val SESSION_CHANGED = "The signed-in user changed"
    private const val NO_SESSION = "No signed-in user"
    private const val INVALID_AUTHOR = "Unexpected photo author"
  }
}
