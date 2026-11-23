// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.photo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.model.image.AndroidTemporaryPhotoStore

/** Each form action supplies a stable key so its preview survives rotation independently. */
@Composable
internal fun photoPickerViewModel(
    key: String,
    personalGalleryOnly: Boolean = false,
): PhotoPickerViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = key,
      factory =
          viewModelFactory {
            initializer {
              PhotoPickerViewModel(
                  AndroidTemporaryPhotoStore(context, personalGalleryOnly),
                  createSavedStateHandle())
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
