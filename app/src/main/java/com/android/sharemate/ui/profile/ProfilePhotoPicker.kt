// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidTemporaryPhotoStore
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.findActivity

/** Key of the camera/gallery ViewModel used to pick a profile photo. */
private const val PROFILE_PHOTO_CAMERA_KEY = "profile-photo"

/**
 * Shows the camera/gallery dialog to pick a profile photo. Open it with
 * [PhotoPickerViewModel.open] on [camera].
 *
 * The dialog's own image is discarded when this leaves composition (but not on rotation), and reset
 * once [onPhotoConfirmed] has taken its copy.
 *
 * @param onPhotoConfirmed Receives the confirmed image URI and whether it is a camera capture. The
 *   URI may only be read until this returns.
 * @param camera The dialog's state, retained in the current ViewModel store.
 * @param title Title of the dialog.
 */
@Composable
fun ProfilePhotoPicker(
    onPhotoConfirmed: suspend (uri: String, fromCamera: Boolean) -> Unit,
    camera: PhotoPickerViewModel = profilePhotoCamera(),
    @StringRes title: Int = R.string.profile_photo_step_title,
) {
  val state by camera.state.collectAsStateWithLifecycle()
  val activity = LocalContext.current.findActivity()
  DisposableEffect(camera) {
    onDispose { if (activity?.isChangingConfigurations != true) camera.discard() }
  }
  LaunchedEffect(state.confirmed) { if (state.confirmed) camera.reset() }
  PhotoPickerDialog(
      camera,
      title = title,
      previewDescription = R.string.profile_photo_preview,
      sourceDescription = R.string.profile_photo_sources,
      onImageConfirmed = { uri -> onPhotoConfirmed(uri, camera.state.value.fromCamera) })
}

/** Returns the camera/gallery ViewModel for the profile photo, retained in the current store. */
@Composable
fun profilePhotoCamera(): PhotoPickerViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = PROFILE_PHOTO_CAMERA_KEY,
      factory =
          viewModelFactory {
            initializer {
              PhotoPickerViewModel(AndroidTemporaryPhotoStore(context), createSavedStateHandle())
            }
          })
}
