package com.android.sharemate.ui.barcode

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.sharemate.R
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Composable
internal fun BarcodeCamera(
    enabled: Boolean,
    onCapture: (suspend (String) -> Unit) -> Unit,
    onFailure: () -> Unit,
    providerLoader: (Context) -> ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider::getInstance,
    imageCaptureFactory: () -> ImageCapture = { ImageCapture.Builder().build() },
) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val previewView = remember {
    PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
  }
  val capture = remember { imageCaptureFactory() }
  val preview = remember { Preview.Builder().build() }
  val executor = remember(context) { ContextCompat.getMainExecutor(context) }
  val failure by rememberUpdatedState(onFailure)
  var ready by remember { mutableStateOf(false) }
  DisposableEffect(owner) {
    var disposed = false
    var provider: ProcessCameraProvider? = null
    val future = providerLoader(context)
    future.addListener(
        {
          if (!disposed) {
            try {
              provider = future.get()
              preview.setSurfaceProvider(previewView.surfaceProvider)
              provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
              ready = true
            } catch (_: Exception) {
              failure()
            }
          }
        },
        executor)
    onDispose {
      disposed = true
      ready = false
      provider?.unbind(preview, capture)
    }
  }
  AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp))
  Button(
      onClick = {
        capture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        onCapture { uri ->
          // Wait for CameraX to finish writing even if the caller is cancelled, then its cleanup is
          // safe.
          suspendCoroutine<Unit> { continuation ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(File(requireNotNull(Uri.parse(uri).path)))
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
      modifier = Modifier.fillMaxWidth().testTag("barcode_photo_capture")) {
        Text(stringResource(R.string.barcode_photo_capture))
      }
}
