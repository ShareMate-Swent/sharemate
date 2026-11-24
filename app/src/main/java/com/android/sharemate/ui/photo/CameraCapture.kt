// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import android.content.Context
import android.net.Uri
import android.view.ViewOutlineProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Shows the CameraX viewfinder, a capture button and, when the device has both a back and a front
 * camera, a button that switches between them.
 *
 * @param lens Preferred lens; when the device lacks it, the other lens is used instead.
 * @param onSwitchLens Called when the user asks for the other lens.
 */
@Composable
internal fun CameraCapture(
    enabled: Boolean,
    onCapture: (suspend (String) -> Unit) -> Unit,
    onFailure: () -> Unit,
    lens: CameraLens = CameraLens.BACK,
    onSwitchLens: () -> Unit = {},
    providerLoader: (Context) -> ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider::getInstance,
    imageCaptureFactory: () -> ImageCapture = { ImageCapture.Builder().build() },
) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val previewView = remember {
    PreviewView(context).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FIT_CENTER
      // AndroidView does not clip the transformed camera texture to its Compose bounds.
      outlineProvider = ViewOutlineProvider.BOUNDS
      clipToOutline = true
    }
  }
  val capture = remember { imageCaptureFactory() }
  val preview = remember { Preview.Builder().build() }
  val executor = remember(context) { ContextCompat.getMainExecutor(context) }
  val failure by rememberUpdatedState(onFailure)
  var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
  var ready by remember { mutableStateOf(false) }
  DisposableEffect(owner) {
    var disposed = false
    val future = providerLoader(context)
    future.addListener(
        {
          if (!disposed) {
            try {
              provider = future.get()
            } catch (_: Exception) {
              failure()
            }
          }
        },
        executor)
    onDispose { disposed = true }
  }
  val lenses = remember(provider) { provider?.availableLenses().orEmpty() }
  // Without the preferred lens (e.g. no front camera) use the other one; with none, let CameraX
  // report the failure.
  val boundLens = if (lens in lenses || lenses.isEmpty()) lens else lens.other()
  DisposableEffect(provider, owner, boundLens) {
    val cameraProvider = provider ?: return@DisposableEffect onDispose {}
    var disposed = false
    previewView.doOnLayout {
      if (!disposed) {
        try {
          // Both streams show the same sensor area while fitting the entire image.
          val useCases =
              UseCaseGroup.Builder()
                  .setViewPort(checkNotNull(previewView.viewPort))
                  .addUseCase(preview)
                  .addUseCase(capture)
                  .build()
          preview.setSurfaceProvider(previewView.surfaceProvider)
          cameraProvider.bindToLifecycle(owner, boundLens.selector(), useCases)
          ready = true
        } catch (_: Exception) {
          failure()
        }
      }
    }
    onDispose {
      disposed = true
      ready = false
      cameraProvider.unbind(preview, capture)
    }
  }
  AndroidView(
      factory = { previewView },
      modifier =
          Modifier.fillMaxWidth()
              .height(PhotoFrameHeight)
              .clipToBounds()
              .testTag(PhotoPickerTestTags.VIEWFINDER))
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
      verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
              capture.targetRotation =
                  previewView.display?.rotation ?: android.view.Surface.ROTATION_0
              onCapture { uri ->
                // Wait for CameraX to finish writing even if the caller is cancelled, then its
                // cleanup is safe.
                suspendCoroutine<Unit> { continuation ->
                  capture.takePicture(
                      ImageCapture.OutputFileOptions.Builder(
                              File(requireNotNull(Uri.parse(uri).path)))
                          .setMetadata(
                              // PreviewView mirrors the front camera; save what the user saw.
                              ImageCapture.Metadata().apply {
                                isReversedHorizontal = boundLens == CameraLens.FRONT
                              })
                          .build(),
                      executor,
                      object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                          continuation.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                          continuation.resumeWithException(exception)
                        }
                      })
                }
              }
            },
            enabled = enabled && ready,
            modifier = Modifier.weight(1f).testTag(PhotoPickerTestTags.CAPTURE)) {
              Text(stringResource(R.string.photo_picker_capture))
            }
        if (lenses.size > 1)
            FilledTonalIconButton(
                onClick = onSwitchLens,
                enabled = enabled && ready,
                modifier = Modifier.testTag(PhotoPickerTestTags.SWITCH_CAMERA)) {
                  Icon(
                      Icons.Filled.Cameraswitch,
                      contentDescription = stringResource(R.string.photo_picker_switch_camera))
                }
      }
}

/** CameraX selector of this lens. */
internal fun CameraLens.selector(): CameraSelector =
    when (this) {
      CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
      CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

/** Lenses this device has; a lens whose info cannot be read counts as missing. */
private fun ProcessCameraProvider.availableLenses(): Set<CameraLens> =
    CameraLens.entries
        .filter { lens ->
          try {
            hasCamera(lens.selector())
          } catch (_: Exception) {
            false
          }
        }
        .toSet()
