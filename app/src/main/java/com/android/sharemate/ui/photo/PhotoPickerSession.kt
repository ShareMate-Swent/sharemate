// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    restoreAfterProcessDeath: Boolean = true,
): PhotoPickerViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = key,
      factory =
          viewModelFactory {
            initializer {
              PhotoPickerViewModel(
                  AndroidTemporaryPhotoStore(context, importPolicy),
                  if (restoreAfterProcessDeath) createSavedStateHandle() else SavedStateHandle(),
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

/**
 * Calls [onCancelled] when the picker closes without a confirmed photo, as after its Cancel button
 * or the back gesture. Closing after a confirmation does not count.
 *
 * @param picker The picker to watch.
 * @param onCancelled Called once per cancellation, with the latest lambda.
 */
@Composable
internal fun OnPhotoPickerCancelled(picker: PhotoPickerViewModel, onCancelled: () -> Unit) {
  val state by picker.state.collectAsStateWithLifecycle()
  val latest by rememberUpdatedState(onCancelled)
  var wasVisible by remember(picker) { mutableStateOf(state.visible) }
  LaunchedEffect(state.visible, state.confirmed) {
    if (state.visible) {
      wasVisible = true
    } else if (wasVisible) {
      wasVisible = false
      if (!state.confirmed) latest()
    }
  }
}
