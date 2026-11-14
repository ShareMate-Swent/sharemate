// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import com.android.sharemate.model.image.PhotoBytes
import com.android.sharemate.model.image.awaitPhotoTransfer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Stores profile photos in Firebase Storage at [ProfilePhotoPaths.avatar] and saves their download
 * URL as the Firebase Auth photo URL.
 */
class FirebaseProfilePhotoRepository
private constructor(
    storageProvider: () -> FirebaseStorage,
    authProvider: () -> FirebaseAuth,
) : ProfilePhotoRepository {
  /** Creates a repository that uses the given Firebase instances. */
  constructor(
      storage: FirebaseStorage,
      auth: FirebaseAuth,
  ) : this({ storage }, { auth })

  private val storage by lazy(storageProvider)
  private val auth by lazy(authProvider)

  override suspend fun upload(expectedUid: String, source: File): Result<String> = outcome {
    val bytes =
        withContext(Dispatchers.IO) {
          val context = currentCoroutineContext()
          source.inputStream().use { PhotoBytes.readBounded(it) { context.ensureActive() } }
        }
    val contentType = PhotoBytes.contentType(bytes)
    val user = auth.currentUser ?: throw IllegalStateException(NO_SIGNED_IN_USER_MESSAGE)
    if (user.uid != expectedUid) throw IllegalStateException(SIGNED_IN_USER_CHANGED_MESSAGE)
    val reference = storage.reference.child(ProfilePhotoPaths.avatar(user.uid))
    val metadata = StorageMetadata.Builder().setContentType(contentType).build()
    currentCoroutineContext().ensureActive()
    reference.putBytes(bytes, metadata).awaitPhotoTransfer()
    // Unlike UploadTask, these SDK Tasks have no cancel operation.
    val url = reference.downloadUrl.await()
    user.updateProfile(UserProfileChangeRequest.Builder().setPhotoUri(url).build()).await()
    url.toString()
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

  /** Builds repositories from the default Firebase instances. */
  companion object {
    private const val NO_SIGNED_IN_USER_MESSAGE = "No Firebase user is signed in"
    private const val SIGNED_IN_USER_CHANGED_MESSAGE = "The signed-in Firebase user changed"

    /** Creates a repository that resolves the default Firebase instances on first use. */
    fun create(): ProfilePhotoRepository =
        FirebaseProfilePhotoRepository(
            { FirebaseStorage.getInstance() }, { FirebaseAuth.getInstance() })
  }
}
