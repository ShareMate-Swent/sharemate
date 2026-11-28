// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import com.android.sharemate.model.user.MAX_PSEUDO_LENGTH
import com.android.sharemate.model.user.PseudoRepository
import com.android.sharemate.model.user.trimPseudoPadding
import com.android.sharemate.model.user.validatePseudo
import com.android.sharemate.ui.profile.ProfilePhotoError
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the pseudo could not be loaded or saved; a load failure blocks saving until retried. */
enum class EditProfilePseudoError {
  LOAD_FAILED,
  SAVE_FAILED,

  /** The pseudo is already claimed by another user. */
  PSEUDO_TAKEN
}

/**
 * Everything the Edit profile page shows: the profile photo and the editable pseudo.
 *
 * @property photoUrl Account photo URL; null when the UI should show a placeholder avatar.
 * @property pseudo The editable public pseudo.
 * @property savedPseudo The last pseudo successfully loaded or saved.
 * @property isLoadingPseudo Whether the public pseudo is being loaded.
 * @property isSavingPseudo Whether a pseudo update is in progress.
 * @property pseudoError The last pseudo load or save failure.
 * @property isUploadingPhoto Whether a new profile photo is being uploaded.
 * @property photoError The last profile photo failure, cleared by the next attempt.
 */
data class EditProfileUiState(
    val photoUrl: String? = null,
    val pseudo: String = "",
    val savedPseudo: String? = null,
    val isLoadingPseudo: Boolean = false,
    val isSavingPseudo: Boolean = false,
    val pseudoError: EditProfilePseudoError? = null,
    val isUploadingPhoto: Boolean = false,
    val photoError: ProfilePhotoError? = null,
) {
  /** Whether the current pseudo can be saved. */
  val canSavePseudo: Boolean
    get() =
        validatePseudo(pseudo) == null &&
            !isLoadingPseudo &&
            pseudoError != EditProfilePseudoError.LOAD_FAILED &&
            !isSavingPseudo &&
            pseudo.trimPseudoPadding() != savedPseudo
}

/**
 * Edits the signed-in user's public pseudo and profile photo.
 *
 * The pseudo is loaded and saved through [PseudoRepository]. A new profile photo is copied into a
 * private file, then uploaded with [ProfilePhotoRepository]. A failed upload keeps the copy so it
 * can be retried; the copy is deleted once uploaded, replaced or when this ViewModel is cleared.
 *
 * @param authRepository Provides the current authenticated session and refreshes it after an
 *   upload.
 * @param pseudoRepository Reads and updates the signed-in user's public pseudo.
 * @param profilePhotoRepository Uploads a new profile photo and makes it the account photo.
 * @param photoFiles Copies the picked image into a private JPEG/PNG file of at most 5 MiB.
 */
class EditProfileViewModel(
    private val authRepository: AuthRepository,
    private val pseudoRepository: PseudoRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val photoFiles: PersonalPhotoFiles,
) : ViewModel() {
  private val mutableState = MutableStateFlow(EditProfileUiState())
  /** The photo and pseudo rendered by the Edit profile page. */
  val state = mutableState.asStateFlow()

  private var currentUserId: String? = null
  private var pendingPhoto: File? = null
  private var photoUpload: Job? = null
  private var cleared = false

  init {
    viewModelScope.launch {
      authRepository.session.filterNotNull().collect { session ->
        mutableState.update { it.copy(photoUrl = session.photoUrl) }
      }
    }
    viewModelScope.launch {
      authRepository.session
          .filterNotNull()
          .map { it.uid }
          .distinctUntilChanged()
          .collectLatest { userId ->
            currentUserId = userId
            mutableState.update {
              it.copy(
                  pseudo = "",
                  savedPseudo = null,
                  isLoadingPseudo = true,
                  isSavingPseudo = false,
                  pseudoError = null)
            }
            loadPseudo(userId)
          }
    }
  }

  /** Updates the pseudo being edited without saving it yet; input past the maximum is dropped. */
  fun updatePseudo(pseudo: String) {
    mutableState.update {
      it.copy(
          pseudo = pseudo.takeCodePoints(MAX_PSEUDO_LENGTH),
          pseudoError =
              it.pseudoError.takeIf { error -> error == EditProfilePseudoError.LOAD_FAILED })
    }
  }

  /** Loads the saved pseudo again after a failure; saving stays blocked until it succeeds. */
  fun retryLoadPseudo() {
    val userId = currentUserId ?: return
    if (state.value.isLoadingPseudo ||
        state.value.pseudoError != EditProfilePseudoError.LOAD_FAILED) {
      return
    }
    mutableState.update { it.copy(isLoadingPseudo = true, pseudoError = null) }
    viewModelScope.launch { loadPseudo(userId) }
  }

  /** Saves the edited pseudo as the public profile name for the current signed-in user. */
  fun savePseudo() {
    val userId = currentUserId ?: return
    val pseudo = state.value.pseudo.trimPseudoPadding()
    if (!state.value.canSavePseudo) return
    mutableState.update { it.copy(isSavingPseudo = true, pseudoError = null) }
    viewModelScope.launch {
      try {
        pseudoRepository.setPseudo(userId, pseudo)
        if (currentUserId == userId) {
          mutableState.update { it.copy(pseudo = pseudo, savedPseudo = pseudo, pseudoError = null) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: AuthException) {
        if (currentUserId == userId) {
          val error =
              if (failure.error == AuthError.PSEUDO_TAKEN) EditProfilePseudoError.PSEUDO_TAKEN
              else EditProfilePseudoError.SAVE_FAILED
          mutableState.update { it.copy(pseudoError = error) }
        }
      } catch (_: Exception) {
        if (currentUserId == userId) {
          mutableState.update { it.copy(pseudoError = EditProfilePseudoError.SAVE_FAILED) }
        }
      } finally {
        if (currentUserId == userId) {
          mutableState.update { it.copy(isSavingPseudo = false) }
        }
      }
    }
  }

  /**
   * Copies the photo confirmed in the camera/gallery dialog, then uploads it as the new profile
   * photo. The page shows it once the session is refreshed. An unreadable photo shows
   * [ProfilePhotoError.INVALID_PHOTO]; a failed upload keeps the old photo and shows
   * [ProfilePhotoError.UPLOAD_FAILED]. Ignored while uploading.
   *
   * @param fromCamera True for a camera capture, which is downscaled and encoded as JPEG.
   */
  suspend fun changePhoto(uri: String, fromCamera: Boolean) {
    if (state.value.isUploadingPhoto) return
    val copy =
        try {
          photoFiles.copy(uri, fromCamera)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          mutableState.update { it.copy(photoError = ProfilePhotoError.INVALID_PHOTO) }
          return
        }
    if (cleared || state.value.isUploadingPhoto) {
      withContext(NonCancellable) { photoFiles.delete(copy) }
      return
    }
    val previous = pendingPhoto
    pendingPhoto = copy
    previous?.let { withContext(NonCancellable) { photoFiles.delete(it) } }
    uploadPhoto()
  }

  /** Uploads the photo of the last failed upload again. */
  fun retryPhotoUpload() {
    if (!state.value.isUploadingPhoto) uploadPhoto()
  }

  private fun uploadPhoto() {
    val photo = pendingPhoto ?: return
    val userId = currentUserId ?: return
    mutableState.update { it.copy(isUploadingPhoto = true, photoError = null) }
    photoUpload =
        viewModelScope.launch {
          val succeeded =
              try {
                profilePhotoRepository.upload(userId, photo).isSuccess
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                false
              } finally {
                mutableState.update { it.copy(isUploadingPhoto = false) }
              }
          if (succeeded) {
            pendingPhoto = null
            authRepository.refreshSession()
            withContext(NonCancellable) { photoFiles.delete(photo) }
          } else {
            mutableState.update { it.copy(photoError = ProfilePhotoError.UPLOAD_FAILED) }
          }
        }
  }

  /** Also deletes a photo not uploaded yet; this outlives [viewModelScope], cancelled here. */
  override fun onCleared() {
    cleared = true
    val pending = photoUpload
    pending?.cancel()
    val photo = pendingPhoto ?: return
    // Let a cancelled upload stop reading the file before it is deleted.
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      photoFiles.delete(photo)
    }
  }

  private suspend fun loadPseudo(userId: String) {
    try {
      val pseudo = pseudoRepository.getPseudo(userId)
      if (currentUserId == userId) {
        mutableState.update {
          it.copy(
              pseudo = pseudo.orEmpty(),
              savedPseudo = pseudo,
              isLoadingPseudo = false,
              pseudoError = null)
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (currentUserId == userId) {
        mutableState.update {
          it.copy(isLoadingPseudo = false, pseudoError = EditProfilePseudoError.LOAD_FAILED)
        }
      }
    } finally {
      if (currentUserId == userId) {
        mutableState.update { it.copy(isLoadingPseudo = false) }
      }
    }
  }
}

private fun String.takeCodePoints(maxCodePoints: Int): String {
  val count = codePointCount(0, length)
  return if (count <= maxCodePoints) this else substring(0, offsetByCodePoints(0, maxCodePoints))
}
