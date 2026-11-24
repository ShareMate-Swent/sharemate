// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.photo

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
import com.android.sharemate.model.image.PhotoImportRejection
import com.android.sharemate.model.image.PhotoImportValidationException
import com.android.sharemate.model.image.TemporaryPhotoStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PhotoPickerDialogTest {
  private val labels =
      PhotoPickerLabels(
          R.string.personal_photo_add,
          R.string.personal_photo_preview,
          R.string.personal_photo_sources)

  @get:Rule val compose = createComposeRule()

  @Test
  fun deniedCameraKeepsGalleryAvailableAndRepeatedClicksWaitForCancellation() {
    shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA)
    val vm = PhotoPickerViewModel(TestPhotoStore(), SavedStateHandle())
    var permissionRequests = 0
    var galleryRequests = 0
    var cancelGallery: () -> Unit = {}
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
                      cancelGallery = { dispatchResult<Uri?>(requestCode, null) }
                    }
                    else -> fail("Unexpected external activity")
                  }
                }
              }
        }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        MaterialTheme { PhotoPickerDialog(vm, labels) }
      }
    }
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).performScrollTo().performClick()
    compose.onNodeWithText("Camera access is needed", substring = true).assertExists()
    compose
        .onNodeWithTag(PhotoPickerTestTags.GALLERY)
        .assertIsEnabled()
        .performScrollTo()
        .performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).performClick()
    compose.runOnIdle {
      assertEquals(1, permissionRequests)
      assertEquals(1, galleryRequests)
      cancelGallery()
      assertNull(vm.state.value.selection?.uri)
      assertFalse(vm.state.value.failed)
      assertFalse(vm.state.value.busy)
    }
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).performClick()
    compose.runOnIdle {
      assertEquals(2, galleryRequests)
      cancelGallery()
    }
    compose
        .onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.photo_picker_camera_settings))
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
    val vm = PhotoPickerViewModel(TestPhotoStore(), SavedStateHandle())
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
        MaterialTheme { PhotoPickerDialog(vm, labels) }
      }
    }
    for (tag in listOf("barcode_photo_camera", "barcode_photo_gallery")) {
      compose.runOnIdle { vm.open() }
      compose.onNodeWithTag(tag).performScrollTo().performClick()
      compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertIsEnabled()
      compose.runOnIdle {
        assertNull(vm.state.value.selection?.uri)
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
    val vm = PhotoPickerViewModel(store, SavedStateHandle())
    val consumed = mutableListOf<String>()
    val pending = CompletableDeferred<Unit>()
    compose.setContent {
      MaterialTheme {
        PhotoPickerDialog(vm, labels) {
          consumed += it.uri
          pending.await()
        }
      }
    }
    compose.runOnIdle {
      vm.open()
      vm.select("content://photos/selected")
    }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertTrue(consumed.isEmpty()) }
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).assertIsNotEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).assertIsNotEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertIsNotEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.LOADING).assertExists()
    compose.runOnIdle {
      assertEquals(listOf(store.uri), consumed)
      assertTrue(File(Uri.parse(store.uri).path!!).exists())
      pending.complete(Unit)
    }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertDoesNotExist()
    compose.runOnIdle { assertTrue(vm.state.value.confirmed) }
  }

  @Test
  fun previewCancellationDoesNotSubmitAndCanReopenSameImage() {
    val store = TestPhotoStore()
    val vm = PhotoPickerViewModel(store, SavedStateHandle())
    compose.setContent {
      MaterialTheme { PhotoPickerDialog(vm, labels) { fail("Cancelled preview submitted") } }
    }
    compose.runOnIdle {
      vm.open()
      vm.select("content://photos/selected")
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertDoesNotExist()
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertEquals(store.uri, vm.state.value.selection?.uri) }
  }

  @Test
  fun sourceScreenOffersCameraAndGalleryAndRecoversAfterError() {
    val vm = PhotoPickerViewModel(TestPhotoStore(), SavedStateHandle())
    compose.setContent { MaterialTheme { PhotoPickerDialog(vm, labels) } }
    compose.runOnIdle {
      vm.open()
      vm.reportFailure()
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).assertIsEnabled()
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).assertIsEnabled()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
    compose.runOnIdle { vm.select("content://photos/retry") }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertDoesNotExist()
  }

  @Test
  fun personalGalleryShowsValidationGuidanceAndUsesGenericMessageForIoErrors() {
    val store = TestPhotoStore()
    val vm = PhotoPickerViewModel(store, SavedStateHandle())
    val context = RuntimeEnvironment.getApplication()
    compose.setContent { MaterialTheme { PhotoPickerDialog(vm, labels) } }
    compose.runOnIdle { vm.open() }

    val cases =
        listOf(
            PhotoImportValidationException(
                PhotoImportRejection.PERSONAL_REQUIREMENTS, "Unsupported image") to
                context.getString(R.string.personal_photo_invalid),
            PhotoImportValidationException(
                PhotoImportRejection.PIXEL_LIMIT, "Image exceeds pixel limit") to
                context.getString(R.string.photo_picker_pixel_limit, 50),
            IOException("Gallery unavailable") to context.getString(R.string.photo_picker_failed))
    for ((failure, expected) in cases) {
      compose.runOnIdle {
        store.importFailure = failure
        vm.select("content://photos/selected")
      }
      compose.onNodeWithText(expected).assertIsDisplayed()
      for ((_, other) in cases) {
        if (other != expected) compose.onNodeWithText(other).assertDoesNotExist()
      }
      compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertDoesNotExist()
    }
  }

  @Test
  fun receiptGalleryImportFailureUsesGenericMessage() {
    val store = TestPhotoStore().apply { importFailure = IOException("Image exceeds size limit") }
    val vm = PhotoPickerViewModel(store, SavedStateHandle())
    val context = RuntimeEnvironment.getApplication()
    val receiptLabels =
        PhotoPickerLabels(
            R.string.scan_receipt, R.string.receipt_photo_preview, R.string.receipt_photo_choose)
    val receiptError = context.getString(R.string.receipt_unreadable)
    compose.setContent { MaterialTheme { PhotoPickerDialog(vm, receiptLabels, receiptError) } }
    compose.runOnIdle {
      vm.open()
      vm.select("content://photos/receipt")
    }

    compose.onNodeWithText(context.getString(R.string.photo_picker_failed)).assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.personal_photo_invalid)).assertDoesNotExist()
    compose.onNodeWithText(receiptError).assertDoesNotExist()
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertDoesNotExist()
  }

  private class TestPhotoStore : TemporaryPhotoStore {
    val uri: String
    var importFailure: IOException? = null

    init {
      val file =
          File.createTempFile("barcode-test", ".png", RuntimeEnvironment.getApplication().cacheDir)
      val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
      uri = Uri.fromFile(file).toString()
    }

    override suspend fun createCapture() = uri

    override suspend fun importImage(uri: String): String {
      importFailure?.let { throw it }
      return this.uri
    }

    override suspend fun validate(uri: String) = Unit

    override suspend fun delete(uri: String) = Unit
  }
}
