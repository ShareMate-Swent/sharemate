// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.image.AndroidPersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks a real camera capture against the personal-photo upload requirements. */
@RunWith(AndroidJUnit4::class)
class PersonalPhotoCameraCaptureTest {
  @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun confirmedCameraCaptureProducesBoundedJpegForPersonalPhoto() {
    val camera = compose.activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    assumeTrue(
        "A back camera is required for this hardware test",
        camera.cameraIdList.any {
          camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
              CameraCharacteristics.LENS_FACING_BACK
        })
    val vm = BarcodePhotoViewModel(AndroidBarcodePhotoStore(compose.activity), SavedStateHandle())
    val photos = AndroidPersonalPhotoFiles(compose.activity)
    var prepared: String? = null
    compose.setContent {
      MaterialTheme {
        BarcodePhotoFlow(
            vm,
            title = R.string.personal_photo_add,
            previewDescription = R.string.personal_photo_preview,
            sourceDescription = R.string.personal_photo_sources) { uri ->
              val copy = photos.copy(uri)
              try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.path, bounds)
                assertEquals("image/jpeg", bounds.outMimeType)
                assertTrue(bounds.outWidth in 1..1280 && bounds.outHeight in 1..1280)
                assertTrue(copy.length() in 1..PersonalPhotoRepository.MAX_BYTES.toLong())
                prepared = uri
              } finally {
                photos.delete(copy)
              }
            }
      }
    }
    try {
      compose.runOnIdle { vm.open() }
      compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).performScrollTo().performClick()
      compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag("barcode_photo_capture").assertIsEnabled() }.isSuccess
      }
      compose.onNodeWithText("Add a personal photo").assertIsDisplayed()
      compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).assertDoesNotExist()
      compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).assertIsDisplayed()
      val frame =
          compose.onNodeWithTag(BarcodePhotoTestTags.VIEWFINDER).fetchSemanticsNode().boundsInRoot
      val shutter = compose.onNodeWithTag("barcode_photo_capture").fetchSemanticsNode().boundsInRoot
      assertTrue(frame.bottom <= shutter.top)
      lateinit var preview: PreviewView
      onView(isAssignableFrom(PreviewView::class.java)).check { view, _ ->
        preview = view as PreviewView
      }
      compose.waitUntil(20_000) {
        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
      }
      val screenshot =
          checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
      try {
        val output =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: checkNotNull(compose.activity.getExternalFilesDir(null))
        check(output.isDirectory || output.mkdirs())
        File(output, "personal-photo-camera-layout.png").outputStream().use {
          check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      } finally {
        screenshot.recycle()
      }
      compose.onNodeWithTag("barcode_photo_capture").performScrollTo().performClick()
      compose.waitUntil(20_000) { vm.state.value.uri != null && !vm.state.value.busy }
      compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
      compose.runOnIdle { assertNull(prepared) }
      compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(10_000) { vm.state.value.confirmed && !vm.state.value.busy }
      compose.runOnIdle { assertEquals(vm.state.value.uri, prepared) }
    } finally {
      compose.runOnIdle { vm.discard() }
    }
  }
}
