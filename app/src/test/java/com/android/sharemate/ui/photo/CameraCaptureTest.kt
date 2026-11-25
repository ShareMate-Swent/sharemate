// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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
class CameraCaptureTest {
  @get:Rule val compose = createComposeRule()
  private val provider = mock(ProcessCameraProvider::class.java)
  private val capture = mock(ImageCapture::class.java)
  private val future = SettableFuture.create<ProcessCameraProvider>()
  private val visible = mutableStateOf(true)
  private val enabled = mutableStateOf(true)
  private val lens = mutableStateOf(CameraLens.BACK)
  private val mirror = mutableStateOf(false)
  private var switches = 0
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var failures = 0

  @After fun tearDown() = scope.cancel()

  private var takePicture: (suspend (String) -> Unit)? = null

  private fun show(onCapture: (suspend (String) -> Unit) -> Unit = { takePicture = it }) {
    compose.setContent {
      MaterialTheme {
        if (visible.value) {
          CameraCapture(
              enabled.value,
              onCapture,
              { failures++ },
              lens = lens.value,
              onSwitchLens = { switches++ },
              mirrorFrontCamera = mirror.value,
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
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsNotEnabled()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsNotEnabled()
    compose.runOnIdle { enabled.value = true }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsEnabled()
    val binding =
        mockingDetails(provider).invocations.single { it.method.name == "bindToLifecycle" }
    assertSame(CameraSelector.DEFAULT_BACK_CAMERA, binding.rawArguments[1])
    val group = binding.rawArguments[2] as UseCaseGroup
    assertEquals(ViewPort.FIT, group.viewPort?.scaleType)
    val useCases = group.useCases.toSet()
    assertTrue(useCases.any { it is Preview })
    assertTrue(capture in useCases)
    compose.runOnIdle { visible.value = false }
    compose.waitForIdle()
    val unbind = mockingDetails(provider).invocations.single { it.method.name == "unbind" }
    assertEquals(useCases, (unbind.rawArguments[0] as Array<*>).toSet())
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
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).performClick()
    compose.runOnIdle {
      assertTrue(results.isEmpty())
      requireNotNull(callback).onImageSaved(ImageCapture.OutputFileResults(null))
    }
    compose.runOnIdle { assertTrue(results.single().isSuccess) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).performClick()
    val error = ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "Camera disconnected", null)
    compose.runOnIdle {
      assertEquals(1, results.size)
      requireNotNull(callback).onError(error)
    }
    compose.runOnIdle { assertSame(error, results[1].exceptionOrNull()) }
    verify(capture, times(2)).setTargetRotation(anyInt())
    assertEquals(0, failures)
  }

  private fun bindings() =
      mockingDetails(provider).invocations.filter { it.method.name == "bindToLifecycle" }

  private fun unbinds() = mockingDetails(provider).invocations.count { it.method.name == "unbind" }

  /** Takes a photo and returns whether it is saved mirrored, like the front-camera preview. */
  private fun captureIsMirrored(): Boolean {
    var options: ImageCapture.OutputFileOptions? = null
    doAnswer {
          options = it.getArgument(0)
          null
        }
        .`when`(capture)
        .takePicture(
            any(ImageCapture.OutputFileOptions::class.java),
            any(Executor::class.java),
            any(ImageCapture.OnImageSavedCallback::class.java))
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).performClick()
    compose.runOnIdle { scope.launch { requireNotNull(takePicture)("file:///private/photo.jpg") } }
    compose.waitForIdle()
    return requireNotNull(options).metadata.isReversedHorizontal
  }

  private fun hasCameras(back: Boolean, front: Boolean) {
    `when`(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(back)
    `when`(provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(front)
  }

  /** Shows the camera on [initial] with both lenses available and waits for the first bind. */
  private fun showWithBothLenses(initial: CameraLens = CameraLens.BACK) {
    hasCameras(back = true, front = true)
    lens.value = initial
    show()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsEnabled()
  }

  @Test
  fun switchIsShownOnlyOnceBothLensesAreKnownAndAsksForTheOtherLens() {
    hasCameras(back = true, front = true)
    show()
    compose.onNodeWithTag(PhotoPickerTestTags.SWITCH_CAMERA).assertDoesNotExist()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag(PhotoPickerTestTags.SWITCH_CAMERA).assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(1, switches) }
  }

  @Test
  fun switchRebindsFrontWithSameViewPortGroup() {
    showWithBothLenses()
    compose.runOnIdle { lens.value = CameraLens.FRONT }
    compose.waitForIdle()
    val (back, front) = bindings()
    assertSame(CameraSelector.DEFAULT_BACK_CAMERA, back.rawArguments[1])
    assertSame(CameraSelector.DEFAULT_FRONT_CAMERA, front.rawArguments[1])
    val group = front.rawArguments[2] as UseCaseGroup
    assertEquals(ViewPort.FIT, group.viewPort?.scaleType)
    assertTrue(group.useCases.any { it is Preview })
    assertTrue(capture in group.useCases)
    assertEquals(1, unbinds())
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsEnabled()
    assertEquals(0, failures)
  }

  @Test
  fun switchIsDisabledWhileBusy() {
    showWithBothLenses()
    compose.runOnIdle { enabled.value = false }
    compose.onNodeWithTag(PhotoPickerTestTags.SWITCH_CAMERA).assertIsNotEnabled()
  }

  @Test
  fun selfieFrontCaptureIsMirrored() {
    mirror.value = true
    showWithBothLenses(CameraLens.FRONT)
    assertTrue(captureIsMirrored())
  }

  @Test
  fun nonSelfieFrontCaptureIsNotMirrored() {
    showWithBothLenses(CameraLens.FRONT)
    assertFalse(captureIsMirrored())
  }

  @Test
  fun selfieBackCaptureIsNotMirrored() {
    mirror.value = true
    showWithBothLenses(CameraLens.BACK)
    assertFalse(captureIsMirrored())
  }

  @Test
  fun frontBindFailureFallsBackToTheBackCameraWithoutAnError() {
    `when`(
            provider.bindToLifecycle(
                any(), eq(CameraSelector.DEFAULT_FRONT_CAMERA), any(UseCaseGroup::class.java)))
        .thenThrow(IllegalArgumentException("Unsupported ViewPort"))
    showWithBothLenses(CameraLens.FRONT)
    val (front, back) = bindings()
    assertSame(CameraSelector.DEFAULT_FRONT_CAMERA, front.rawArguments[1])
    assertSame(CameraSelector.DEFAULT_BACK_CAMERA, back.rawArguments[1])
    compose.onNodeWithTag(PhotoPickerTestTags.SWITCH_CAMERA).assertDoesNotExist()
    assertEquals(0, failures)
  }

  @Test
  fun bindFailureWithoutAnotherLensReportsAnError() {
    hasCameras(back = true, front = false)
    `when`(
            provider.bindToLifecycle(
                any(), eq(CameraSelector.DEFAULT_BACK_CAMERA), any(UseCaseGroup::class.java)))
        .thenThrow(IllegalArgumentException("Camera in use"))
    show()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsNotEnabled()
    compose.runOnIdle { assertEquals(1, failures) }
  }

  @Test
  fun missingFrontCameraHidesTheSwitchAndFallsBackToTheBackCamera() {
    hasCameras(back = true, front = false)
    lens.value = CameraLens.FRONT
    show()
    compose.runOnIdle { future.set(provider) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.SWITCH_CAMERA).assertDoesNotExist()
    assertSame(CameraSelector.DEFAULT_BACK_CAMERA, bindings().single().rawArguments[1])
    assertFalse(captureIsMirrored())
    assertEquals(0, failures)
  }

  @Test
  fun providerFailureReportsErrorAndNeverEnablesCapture() {
    show()
    compose.runOnIdle { future.setException(IOException("Camera unavailable")) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsNotEnabled()
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
