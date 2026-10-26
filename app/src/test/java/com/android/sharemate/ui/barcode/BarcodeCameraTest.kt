package com.android.sharemate.ui.barcode

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

/** Verify CameraX callbacks and lifecycle without relying on a CI device having a camera. */
@RunWith(RobolectricTestRunner::class)
class BarcodeCameraTest {
  @get:Rule val compose = createComposeRule()
  private val provider = mock(ProcessCameraProvider::class.java)
  private val capture = mock(ImageCapture::class.java)
  private val future = SettableFuture.create<ProcessCameraProvider>()
  private val visible = mutableStateOf(true)
  private val enabled = mutableStateOf(true)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var failures = 0

  @After fun tearDown() = scope.cancel()

  private fun show(onCapture: (suspend (String) -> Unit) -> Unit = {}) {
    compose.setContent {
      MaterialTheme {
        if (visible.value) {
          BarcodeCamera(
              enabled.value,
              onCapture,
              { failures++ },
              providerLoader = { future },
              imageCaptureFactory = { capture })
        }
      }
    }
  }

  @Test
  fun waitsForProviderHonorsDisabledStateAndUnbindsOnlyItsOwnUseCases() {
    enabled.value = false
    show()
    compose.onNodeWithTag("barcode_photo_capture").assertIsNotEnabled()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag("barcode_photo_capture").assertIsNotEnabled()
    compose.runOnIdle { enabled.value = true }
    compose.onNodeWithTag("barcode_photo_capture").assertIsEnabled()
    val binding =
        mockingDetails(provider).invocations.single { it.method.name == "bindToLifecycle" }
    assertSame(CameraSelector.DEFAULT_BACK_CAMERA, binding.rawArguments[1])
    val useCases = binding.rawArguments[2] as Array<*>
    assertTrue(useCases[0] is Preview)
    assertSame(capture, useCases[1])
    compose.runOnIdle { visible.value = false }
    compose.waitForIdle()
    val unbind = mockingDetails(provider).invocations.single { it.method.name == "unbind" }
    assertArrayEquals(useCases, unbind.rawArguments[0] as Array<*>)
    assertFalse(mockingDetails(provider).invocations.any { it.method.name == "unbindAll" })
    assertEquals(0, failures)
  }

  @Test
  fun captureWaitsForCameraCallbackAndPropagatesSuccessOrOriginalError() {
    var callback: ImageCapture.OnImageSavedCallback? = null
    doAnswer {
          callback = it.getArgument(2)
          null
        }
        .`when`(capture)
        .takePicture(
            any(ImageCapture.OutputFileOptions::class.java),
            any(Executor::class.java),
            any(ImageCapture.OnImageSavedCallback::class.java))
    val results = mutableListOf<Result<Unit>>()
    show { takePicture ->
      scope.launch { results += runCatching { takePicture("file:///private/photo.jpg") } }
    }
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag("barcode_photo_capture").performClick()
    compose.runOnIdle {
      assertTrue(results.isEmpty())
      requireNotNull(callback).onImageSaved(ImageCapture.OutputFileResults(null))
    }
    compose.runOnIdle { assertTrue(results.single().isSuccess) }
    compose.onNodeWithTag("barcode_photo_capture").performClick()
    val error = ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "Camera disconnected", null)
    compose.runOnIdle {
      assertEquals(1, results.size)
      requireNotNull(callback).onError(error)
    }
    compose.runOnIdle { assertSame(error, results[1].exceptionOrNull()) }
    verify(capture, times(2)).setTargetRotation(anyInt())
    assertEquals(0, failures)
  }

  @Test
  fun providerFailureReportsErrorAndNeverEnablesCapture() {
    show()
    compose.runOnIdle { future.setException(IOException("Camera unavailable")) }
    compose.onNodeWithTag("barcode_photo_capture").assertIsNotEnabled()
    compose.runOnIdle { assertEquals(1, failures) }
    verifyNoInteractions(provider)
  }

  @Test
  fun leavingBeforeProviderCompletesDoesNotBindOrReportAnError() {
    show()
    compose.runOnIdle { visible.value = false }
    compose.waitForIdle()
    compose.runOnIdle { future.set(provider) }
    compose.waitForIdle()
    verifyNoInteractions(provider)
    assertEquals(0, failures)
  }
}
