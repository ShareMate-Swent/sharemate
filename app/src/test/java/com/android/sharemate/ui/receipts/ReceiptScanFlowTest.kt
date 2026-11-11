// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.R
import com.android.sharemate.model.image.BarcodePhotoStore
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReceiptScanFlowTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun balancesEntryTracksPrivateAndSharedHouseholdPolicy() {
    val store =
        object : BarcodePhotoStore {
          override suspend fun createCapture(): String = error("unused")

          override suspend fun importImage(uri: String): String = error("unused")

          override suspend fun validate(uri: String) = Unit

          override suspend fun delete(uri: String) = Unit
        }
    val photo = BarcodePhotoViewModel(store, SavedStateHandle())
    var shared by androidx.compose.runtime.mutableStateOf(false)
    var opened = 0
    compose.setContent { ReceiptScanFlow(photo, shared, { opened++ }) { emptyList() } }
    compose.onNodeWithTag("receipts_balances").assertDoesNotExist()
    compose.runOnIdle { shared = true }
    compose.onNodeWithTag("receipts_balances").performClick()
    assertEquals(1, opened)
    compose.runOnIdle { shared = false }
    compose.onNodeWithTag("receipts_balances").assertDoesNotExist()
  }

  @Test
  fun unreadablePhotoCanBeReplacedAndNewItemsReachReviewExactlyOnce() {
    val app = RuntimeEnvironment.getApplication()

    val file = File.createTempFile("receipt-input", ".png", app.cacheDir)
    try {
      file.outputStream().use {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      val store =
          object : BarcodePhotoStore {
            override suspend fun createCapture() = error("Unexpected camera call")

            override suspend fun importImage(uri: String) = Uri.fromFile(file).toString()

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          }
      val model = BarcodePhotoViewModel(store, SavedStateHandle())
      var reads = 0
      val restoration = StateRestorationTester(compose)
      restoration.setContent {
        ReceiptScanFlow(model) {
          reads++
          if (reads == 1) emptyList()
          else listOf(ReceiptItem("Lait", 240), ReceiptItem("Pain", 310))
        }
      }
      compose.onNodeWithText(app.getString(R.string.scan_receipt)).performClick()
      fun selectAndConfirm() {
        compose.runOnIdle { model.select("content://picker/receipt") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.uri != null }
        compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
      }
      selectAndConfirm()
      compose.waitUntil(10000) { model.state.value.confirmationFailed && !model.state.value.busy }
      compose.onNodeWithText(app.getString(R.string.receipt_unreadable)).assertExists()
      compose.onNodeWithText(app.getString(R.string.staging_title)).assertDoesNotExist()
      selectAndConfirm()
      compose.waitUntil(10000) { model.state.value.confirmed && !model.state.value.busy }
      compose.onNodeWithText(app.getString(R.string.staging_title)).assertExists()
      compose.onNodeWithText("Lait").assertExists()
      compose.onNodeWithText("Pain").assertExists()
      compose.onNodeWithText("CHF 2.40").assertExists()
      compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
      compose.onNodeWithText(app.getString(R.string.scan_receipt)).assertExists()
      restoration.emulateSavedInstanceStateRestore()
      compose.onNodeWithText(app.getString(R.string.staging_title)).performClick()
      compose.onNodeWithText("Lait").assertExists()
      compose.onNodeWithText("CHF 2.40").assertExists()
      compose.onNodeWithText("CHF 3.10").assertExists()
      compose.onNodeWithText("Pain").assertExists()
      assertEquals(2, reads)
    } finally {
      file.delete()
    }
  }
}
