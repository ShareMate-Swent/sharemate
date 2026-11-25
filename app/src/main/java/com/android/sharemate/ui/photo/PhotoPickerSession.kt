// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.model.image.AndroidTemporaryPhotoStore
import com.android.sharemate.model.image.PhotoImportPolicy

/** Each form action supplies a stable key so its preview survives rotation independently. */
@Composable
internal fun photoPickerViewModel(
    key: String,
    importPolicy: PhotoImportPolicy = PhotoImportPolicy.STANDARD,
    defaultLens: CameraLens = CameraLens.BACK,
    mirrorFrontCamera: Boolean = false,
): PhotoPickerViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = key,
      factory =
          viewModelFactory {
            initializer {
              PhotoPickerViewModel(
                  AndroidTemporaryPhotoStore(context, importPolicy),
                  createSavedStateHandle(),
                  defaultLens,
                  mirrorFrontCamera)
            }
          })
}

/** Scope cleanup to the host form, which can outlive the picker dialog. */
@Composable
internal fun DiscardPhotoOnDispose(picker: PhotoPickerViewModel) {
  val activity = LocalContext.current.findActivity()
  DisposableEffect(picker, activity) {
    onDispose { if (activity?.isChangingConfigurations != true) picker.discard() }
  }
}
