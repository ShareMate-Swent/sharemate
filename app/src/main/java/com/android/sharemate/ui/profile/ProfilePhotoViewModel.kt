// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Errors shown by the profile photo step. */
enum class ProfilePhotoError {
  /** The picked image could not be copied: unreadable, not JPEG/PNG, or larger than 5 MiB. */
  INVALID_PHOTO,
  /** The photo could not be uploaded or set as the account photo. */
  UPLOAD_FAILED,
}

/**
 * State of the profile photo step.
 *
 * @property photo Private copy of the picked photo, shown as a preview and uploaded on save.
 * @property isImporting True while the latest selection is being copied.
 * @property isUploading True while the photo is being uploaded.
 * @property error The last failure, cleared by the next action.
 * @property done True once the photo was saved or the step was skipped.
 */
data class ProfilePhotoUiState(
    val photo: File? = null,
    val isUploading: Boolean = false,
    val error: ProfilePhotoError? = null,
    val done: Boolean = false,
    val isImporting: Boolean = false,
)

/**
 * Lets a newly signed-up user pick, preview and save a profile photo, or skip the step.
 *
 * The picked image is copied into a private file owned by this ViewModel; that file is deleted when
 * it is replaced, once the step is done, and when the ViewModel is cleared.
 *
 * @param repository Uploads the photo and makes it the account photo.
 * @param files Copies the picked image into a private JPEG/PNG file of at most 5 MiB.
 * @param uid The user who signed up; the upload is refused if another user is signed in.
 */
class ProfilePhotoViewModel(
    private val repository: ProfilePhotoRepository,
    private val files: PersonalPhotoFiles,
    private val uid: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(ProfilePhotoUiState())

  /** The current state of the step. */
  val state = mutableState.asStateFlow()

  private var upload: Job? = null
  private var cleared = false
  // Counts the selections started; only the latest one may change the preview or show an error.
  private var latestSelection = 0

  /**
   * Copies the image at [uri] and shows it as the preview, replacing the previous one. On failure
   * the previous photo is kept and [ProfilePhotoError.INVALID_PHOTO] is shown. Ignored while
   * uploading or once done. When selections overlap, only the latest one counts: an older copy that
   * finishes last is deleted, and its failure is not shown.
   *
   * @param fromCamera True for a camera capture, which is downscaled and encoded as JPEG.
   */
  suspend fun select(uri: String, fromCamera: Boolean) {
    if (!acceptsActions()) return
    val selection = ++latestSelection
    mutableState.update { it.copy(isImporting = true, error = null) }
    try {
      val copy =
          try {
            files.copy(uri, fromCamera)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (selection == latestSelection && acceptsActions()) {
              mutableState.update { it.copy(error = ProfilePhotoError.INVALID_PHOTO) }
            }
            return
          }
      if (cleared || !acceptsActions() || selection != latestSelection) {
        withContext(NonCancellable) { files.delete(copy) }
        return
      }
      val previous = state.value.photo
      mutableState.update { it.copy(photo = copy, error = null) }
      previous?.let { withContext(NonCancellable) { files.delete(it) } }
    } finally {
      if (selection == latestSelection) mutableState.update { it.copy(isImporting = false) }
    }
  }

  /**
   * Copies an image picked straight from the gallery and shows it, like [select]. The copy runs in
   * the ViewModel's scope, so it is not lost if the screen is recreated while it runs.
   */
  fun selectFromGallery(uri: String) {
    viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { select(uri, fromCamera = false) }
  }

  /**
   * Uploads the previewed photo. On success the step is done; on failure the photo is kept so it
   * can be retried, and [ProfilePhotoError.UPLOAD_FAILED] is shown. Ignored without a photo, while
   * uploading or once done.
   */
  fun save() {
    val photo = state.value.photo
    if (photo == null || state.value.isImporting || !acceptsActions()) return
    mutableState.update { it.copy(isUploading = true, error = null) }
    upload =
        viewModelScope.launch {
          val succeeded =
              try {
                repository.upload(uid, photo).isSuccess
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                false
              } finally {
                mutableState.update { it.copy(isUploading = false) }
              }
          if (succeeded) {
            finish()
          } else {
            mutableState.update { it.copy(error = ProfilePhotoError.UPLOAD_FAILED) }
          }
        }
  }

  /**
   * Ends the step without saving a photo, cancelling an upload in progress (for example one that
   * waits for the network), so the user is never stuck here. A cancelled upload keeps the previous
   * account photo. Ignored once done.
   */
  fun skip() {
    if (state.value.done) return
    upload?.cancel()
    finish()
  }

  private fun acceptsActions(): Boolean = !state.value.isUploading && !state.value.done

  private fun finish() {
    val photo = state.value.photo
    val pending = upload
    mutableState.update { it.copy(photo = null, error = null, done = true, isImporting = false) }
    // Let a cancelled upload stop reading the file before it is deleted.
    photo?.let {
      viewModelScope.launch(NonCancellable) {
        pending?.join()
        files.delete(it)
      }
    }
  }

  /** Also deletes the photo; this outlives [viewModelScope], which is already cancelled here. */
  override fun onCleared() {
    cleared = true
    val pending = upload
    pending?.cancel()
    val photo = state.value.photo ?: return
    // Let a cancelled upload stop reading the file before it is deleted.
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      files.delete(photo)
    }
  }
}
