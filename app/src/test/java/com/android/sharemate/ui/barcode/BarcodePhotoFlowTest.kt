// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.barcode

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.R
import com.android.sharemate.model.image.BarcodePhotoStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BarcodePhotoFlowTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun deniedCameraKeepsGalleryAvailableAndPickerCancellationIsSilent() {
    shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA)
    val vm = BarcodePhotoViewModel(TestPhotoStore(), SavedStateHandle())
    var permissionRequests = 0
    var galleryRequests = 0
    val owner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry =
              object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                  when (contract) {
                    is ActivityResultContracts.RequestPermission -> {
                      assertEquals(Manifest.permission.CAMERA, input)
                      permissionRequests++
                      dispatchResult(requestCode, false)
                    }
                    is ActivityResultContracts.PickVisualMedia -> {
                      galleryRequests++
                      dispatchResult<Uri?>(requestCode, null)
                    }
                    else -> fail("Unexpected external activity")
                  }
                }
              }
        }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        MaterialTheme { BarcodePhotoFlow(vm) }
      }
    }
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).performScrollTo().performClick()
    compose.onNodeWithText("Camera access is needed", substring = true).assertExists()
    compose
        .onNodeWithTag(BarcodePhotoTestTags.GALLERY)
        .assertIsEnabled()
        .performScrollTo()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, permissionRequests)
      assertEquals(1, galleryRequests)
      assertNull(vm.state.value.uri)
      assertFalse(vm.state.value.failed)
      assertFalse(vm.state.value.busy)
    }
    compose
        .onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.barcode_camera_settings))
        .performScrollTo()
        .performClick()
    compose.runOnIdle {
      val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
      assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
      assertEquals("package:${RuntimeEnvironment.getApplication().packageName}", intent.dataString)
    }
  }

  @Test
  fun unavailableCameraPermissionOrGalleryActivityShowsRecoverableError() {
    shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA)
    val vm = BarcodePhotoViewModel(TestPhotoStore(), SavedStateHandle())
    val owner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry =
              object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                  throw ActivityNotFoundException("No handler")
                }
              }
        }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        MaterialTheme { BarcodePhotoFlow(vm) }
      }
    }
    for (tag in listOf("barcode_photo_camera", "barcode_photo_gallery")) {
      compose.runOnIdle { vm.open() }
      compose.onNodeWithTag(tag).performScrollTo().performClick()
      compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
      compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).assertIsEnabled()
      compose.runOnIdle {
        assertNull(vm.state.value.uri)
        assertFalse(vm.state.value.busy)
      }
    }
  }

  @Test
  fun activityLookupSafelyHandlesWrappedNonActivityContexts() {
    val application = RuntimeEnvironment.getApplication()
    assertNull(application.findActivity())
    assertNull(ContextWrapper(ContextWrapper(application)).findActivity())
  }

  @Test
  fun previewWaitsForConfirmationAndLocksActionsUntilConsumerFinishes() {
    val store = TestPhotoStore()
    val vm = BarcodePhotoViewModel(store, SavedStateHandle())
    val consumed = mutableListOf<String>()
    val pending = CompletableDeferred<Unit>()
    compose.setContent {
      MaterialTheme {
        BarcodePhotoFlow(vm) {
          consumed += it
          pending.await()
        }
      }
    }
    compose.runOnIdle {
      vm.open()
      vm.select("content://photos/selected")
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertTrue(consumed.isEmpty()) }
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).assertIsNotEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).assertIsNotEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).assertIsNotEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.LOADING).assertExists()
    compose.runOnIdle {
      assertEquals(listOf(store.uri), consumed)
      assertTrue(File(Uri.parse(store.uri).path!!).exists())
      pending.complete(Unit)
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertDoesNotExist()
    compose.runOnIdle { assertTrue(vm.state.value.confirmed) }
  }

  @Test
  fun previewCancellationDoesNotSubmitAndCanReopenSameImage() {
    val store = TestPhotoStore()
    val vm = BarcodePhotoViewModel(store, SavedStateHandle())
    compose.setContent {
      MaterialTheme { BarcodePhotoFlow(vm) { fail("Cancelled preview submitted") } }
    }
    compose.runOnIdle {
      vm.open()
      vm.select("content://photos/selected")
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertDoesNotExist()
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertEquals(store.uri, vm.state.value.uri) }
  }

  @Test
  fun sourceScreenOffersCameraAndGalleryAndRecoversAfterError() {
    val vm = BarcodePhotoViewModel(TestPhotoStore(), SavedStateHandle())
    compose.setContent { MaterialTheme { BarcodePhotoFlow(vm) } }
    compose.runOnIdle {
      vm.open()
      vm.reportFailure()
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).assertIsEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).assertIsEnabled()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
    compose.runOnIdle { vm.select("content://photos/retry") }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertDoesNotExist()
  }

  private class TestPhotoStore : BarcodePhotoStore {
    val uri: String

    init {
      val file =
          File.createTempFile("barcode-test", ".png", RuntimeEnvironment.getApplication().cacheDir)
      val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
      uri = Uri.fromFile(file).toString()
    }

    override suspend fun createCapture() = uri

    override suspend fun importImage(uri: String) = this.uri

    override suspend fun validate(uri: String) = Unit

    override suspend fun delete(uri: String) = Unit
  }
}
