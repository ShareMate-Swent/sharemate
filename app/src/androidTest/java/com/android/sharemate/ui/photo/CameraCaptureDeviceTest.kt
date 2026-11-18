// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.photo

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.AndroidTemporaryPhotoStore
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodeFormTestTags
import com.android.sharemate.ui.fridge.AddItemDialog
import com.android.sharemate.ui.fridge.FridgeFormError
import com.android.sharemate.ui.fridge.FridgeUiState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real CameraX smoke test; camera-less CI devices cannot exercise physical capture. */
@RunWith(AndroidJUnit4::class)
class CameraCaptureDeviceTest {
  @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun invalidManualSaveDoesNotDiscardConfirmedPhoto() {
    val vm = PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
    val owner =
        object : ViewModelStoreOwner {
          override val viewModelStore = ViewModelStore().apply { put("add-item-barcode-photo", vm) }
        }
    val form = mutableStateOf(FridgeUiState(fridgeType = FridgeType.SHARED))
    compose.setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        MaterialTheme {
          Box(Modifier.fillMaxSize()) {
            AddItemDialog(
                form.value,
                {},
                {},
                {},
                { form.value = form.value.copy(formError = FridgeFormError.NAME_REQUIRED) },
                {})
          }
        }
      }
    }
    compose.runOnIdle {
      vm.open()
      vm.capture { uri ->
        withContext(Dispatchers.IO) {
          val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
          File(Uri.parse(uri).path!!).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
          bitmap.recycle()
        }
      }
    }
    compose.waitUntil(10_000) { vm.state.value.selection?.uri != null && !vm.state.value.busy }
    compose.runOnIdle { vm.confirm() }
    compose.waitUntil(10_000) { vm.state.value.confirmed && !vm.state.value.busy }
    val selected = vm.state.value.selection?.uri
    compose
        .onNodeWithText(compose.activity.getString(R.string.barcode_photo_selected))
        .assertExists()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_form_error).assertExists()
    compose
        .onNodeWithText(compose.activity.getString(R.string.barcode_photo_selected))
        .assertExists()
    compose.runOnIdle {
      assertEquals(selected, vm.state.value.selection?.uri)
      owner.viewModelStore.clear()
    }
  }

  @Test
  fun cameraFlowCancellationReturnsToUnchangedManualForm() {
    var dismissed = false
    compose.setContent {
      MaterialTheme {
        Box(Modifier.fillMaxSize()) {
          AddItemDialog(
              FridgeUiState(
                  itemName = "Milk",
                  itemQuantity = "2",
                  itemCategory = "Dairy",
                  fridgeType = FridgeType.SHARED),
              {},
              {},
              {},
              {},
              { dismissed = true })
        }
      }
    }
    compose.onNodeWithTag(BarcodeFormTestTags.OPEN).performScrollTo().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Milk")
    compose.onNodeWithTag(C.Tag.fridge_quantity_input).assertTextContains("2")
    compose.onNodeWithTag(C.Tag.fridge_category_input).assertTextContains("Dairy")
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsDisplayed()
    compose.runOnIdle { assertFalse(dismissed) }
  }

  @Test
  fun cameraCapturesReadablePhotoAndHandsItOffAfterConfirmation() {
    val camera = compose.activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    assumeTrue(
        "A back camera is required for this hardware test",
        camera.cameraIdList.any {
          camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
              CameraCharacteristics.LENS_FACING_BACK
        })
    val store = AndroidTemporaryPhotoStore(compose.activity)
    val vm = PhotoPickerViewModel(store, SavedStateHandle())
    var handedOff: String? = null
    compose.setContent {
      MaterialTheme {
        PhotoPickerDialog(
            vm,
            PhotoPickerLabels(
                R.string.personal_photo_add,
                R.string.personal_photo_preview,
                R.string.personal_photo_sources)) { photo ->
              store.validate(photo.uri)
              handedOff = photo.uri
            }
      }
    }
    compose.runOnIdle { vm.open() }
    compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).performScrollTo().performClick()
    compose.waitUntil(20_000) {
      runCatching { compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).assertIsEnabled() }.isSuccess
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CAPTURE).performScrollTo().performClick()
    compose.waitUntil(20_000) { vm.state.value.selection?.uri != null && !vm.state.value.busy }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertEquals(null, handedOff) }
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    compose.waitUntil(10_000) { vm.state.value.confirmed && !vm.state.value.busy }
    compose.runOnIdle {
      assertEquals(vm.state.value.selection?.uri, handedOff)
      vm.discard()
    }
  }
}
