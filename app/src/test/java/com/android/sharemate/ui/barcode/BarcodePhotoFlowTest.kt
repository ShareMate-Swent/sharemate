package com.android.sharemate.ui.barcode

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
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
    compose.onNodeWithTag("barcode_photo_camera").performScrollTo().performClick()
    compose.onNodeWithText("Camera access is needed", substring = true).assertExists()
    compose
        .onNodeWithTag("barcode_photo_gallery")
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
    compose.onNodeWithTag("barcode_photo_preview").assertIsDisplayed()
    compose.runOnIdle { assertTrue(consumed.isEmpty()) }
    compose.onNodeWithTag("barcode_photo_confirm").performScrollTo().performClick()
    compose.onNodeWithTag("barcode_photo_confirm").assertIsNotEnabled()
    compose.onNodeWithTag("barcode_photo_gallery").assertIsNotEnabled()
    compose.onNodeWithTag("barcode_photo_camera").assertIsNotEnabled()
    compose.onNodeWithTag("barcode_photo_cancel").assertIsNotEnabled()
    compose.onNodeWithTag("barcode_photo_loading").assertExists()
    compose.runOnIdle {
      assertEquals(listOf(store.uri), consumed)
      assertTrue(File(Uri.parse(store.uri).path!!).exists())
      pending.complete(Unit)
    }
    compose.onNodeWithTag("barcode_photo_preview").assertDoesNotExist()
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
    compose.onNodeWithTag("barcode_photo_cancel").performScrollTo().performClick()
    compose.onNodeWithTag("barcode_photo_preview").assertDoesNotExist()
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag("barcode_photo_preview").assertIsDisplayed()
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
    compose.onNodeWithTag("barcode_photo_camera").assertIsEnabled()
    compose.onNodeWithTag("barcode_photo_gallery").assertIsEnabled()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
    compose.runOnIdle { vm.select("content://photos/retry") }
    compose.onNodeWithTag("barcode_photo_preview").assertIsDisplayed()
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
