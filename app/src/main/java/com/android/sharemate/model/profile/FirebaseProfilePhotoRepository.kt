// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import com.android.sharemate.model.image.PhotoBytes
import com.android.sharemate.model.image.awaitPhotoTransfer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Stores profile photos in Firebase Storage at a new [ProfilePhotoPaths.avatar] for each upload,
 * saves their download URL as the Firebase Auth photo URL, then deletes the photo the account
 * pointed to before.
 */
class FirebaseProfilePhotoRepository
private constructor(
    storageProvider: () -> FirebaseStorage,
    authProvider: () -> FirebaseAuth,
    private val newPhotoId: () -> String,
) : ProfilePhotoRepository {
  /** Creates a repository that uses the given Firebase instances. */
  constructor(
      storage: FirebaseStorage,
      auth: FirebaseAuth,
  ) : this(storage, auth, ::randomPhotoId)

  /** Creates a repository that names new photos with [newPhotoId], for tests. */
  internal constructor(
      storage: FirebaseStorage,
      auth: FirebaseAuth,
      newPhotoId: () -> String,
  ) : this({ storage }, { auth }, newPhotoId)

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
    val previousUrl = user.photoUrl?.toString()
    val reference = storage.reference.child(ProfilePhotoPaths.avatar(user.uid, newPhotoId()))
    val metadata = StorageMetadata.Builder().setContentType(contentType).build()
    currentCoroutineContext().ensureActive()
    reference.putBytes(bytes, metadata).awaitPhotoTransfer()
    // Until the profile update succeeds, the previous photo is left untouched. A failure here
    // leaves the new object unused; it is not deleted, because a failed profile update may still
    // have reached the server. Unlike UploadTask, these SDK Tasks have no cancel operation.
    val url = reference.downloadUrl.await()
    user.updateProfile(UserProfileChangeRequest.Builder().setPhotoUri(url).build()).await()
    deletePreviousPhoto(user.uid, previousUrl, reference.name)
    url.toString()
  }

  /**
   * Deletes the photo at [previousUrl], the account photo before this upload, if it is one of
   * [uid]'s profile photos other than [currentName]. Failures are ignored: the new photo is already
   * the account photo, so a photo left behind only uses storage. Runs even if the caller is
   * cancelled.
   *
   * Only that photo is deleted, never the rest of the folder: if uploads overlap (for example on
   * two devices), another upload's photo may have become the account photo in the meantime. Photos
   * left unused by overlapping or failed uploads are kept.
   */
  private suspend fun deletePreviousPhoto(uid: String, previousUrl: String?, currentName: String) =
      withContext(NonCancellable) {
        try {
          val root = storage.reference
          val path = ProfilePhotoPaths.objectPath(previousUrl ?: return@withContext, root.bucket)
          val name = path?.substringAfterLast('/') ?: return@withContext
          if (name != currentName && path == ProfilePhotoPaths.avatar(uid, name)) {
            root.child(path).delete().await()
          }
        } catch (error: Exception) {
          // Not one of this user's photos, or the delete failed: nothing else to do.
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

  /** Builds repositories from the default Firebase instances. */
  companion object {
    private const val NO_SIGNED_IN_USER_MESSAGE = "No Firebase user is signed in"
    private const val SIGNED_IN_USER_CHANGED_MESSAGE = "The signed-in Firebase user changed"

    /** Creates a repository that resolves the default Firebase instances on first use. */
    fun create(): ProfilePhotoRepository =
        FirebaseProfilePhotoRepository(
            { FirebaseStorage.getInstance() }, { FirebaseAuth.getInstance() }, ::randomPhotoId)

    /** Returns a new random photo name, so each upload gets its own Storage path. */
    private fun randomPhotoId(): String = UUID.randomUUID().toString()
  }
}
