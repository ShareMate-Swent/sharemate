// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.R
import com.android.sharemate.ReceiptToFridgeTestActivity
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BarcodePurchaseFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ReceiptToFridgeTestActivity>()

  @Test
  fun cancelledAndFailedScansRetainRowsAndAllowManualEntry() {
    val file = File.createTempFile("barcode-purchase", ".png", compose.activity.cacheDir)
    val owners = ViewModelStore()
    try {
      file.outputStream().use {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      val store =
          object : TemporaryPhotoStore {
            override suspend fun createCapture(): String = error("unused")

            override suspend fun importImage(uri: String) = Uri.fromFile(file).toString()

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          }
      var lookups = 0
      var unreadable = false
      val barcode =
          PurchaseBarcodeViewModel(
              BarcodeScanner {
                if (unreadable) BarcodeScanResult.NotFound
                else BarcodeScanResult.Found("7612345678901")
              },
              object : ProductCatalogRepository {
                override suspend fun lookupByBarcode(barcode: String): ProductLookupResult {
                  lookups++
                  return when (lookups) {
                    1 -> ProductLookupResult.Found(ProductDetails(barcode, "Scanned milk", null))
                    2 -> ProductLookupResult.NotFound
                    3 -> ProductLookupResult.Found(ProductDetails(barcode, null, null))
                    else -> ProductLookupResult.Failure(ProductLookupResult.Reason.NETWORK)
                  }
                }
              },
              SavedStateHandle())
      val photo = PhotoPickerViewModel(store, SavedStateHandle())
      val receipt = PhotoPickerViewModel(store, SavedStateHandle())
      compose.runOnIdle {
        owners.put("barcode", barcode)
        owners.put("photo", photo)
        owners.put("receipt", receipt)
      }
      compose.setContent {
        MaterialTheme {
          ReceiptScanFlow(receipt, { emptyList() }, null, barcode = barcode, barcodePhoto = photo)
        }
      }
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).performClick()
      fun openScan() {
        compose.onNodeWithTag(PurchaseBarcodeTestTags.ADD).performScrollTo().performClick()
      }
      fun confirmPhoto() {
        compose.runOnIdle { photo.select("fixture://milk") }
        compose.waitUntil(10_000) { !photo.state.value.busy && photo.state.value.uri != null }
        compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
      }
      confirmPhoto()
      compose.waitUntil(10_000) { photo.state.value.confirmed }
      compose.onNodeWithText("Scanned milk").assertExists()
      openScan()
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
      compose.onNodeWithText("Scanned milk").assertExists()
      assertEquals(1, lookups)
      openScan()
      confirmPhoto()
      compose.waitUntil(10_000) { photo.state.value.confirmationFailed && !photo.state.value.busy }
      compose
          .onAllNodesWithText(compose.activity.getString(R.string.barcode_lookup_not_found))
          .onFirst()
          .assertExists()
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
      compose.onNodeWithText("Scanned milk").assertExists()
      for (message in
          listOf(
              R.string.barcode_lookup_no_details,
              R.string.barcode_lookup_failed,
              R.string.barcode_lookup_unreadable)) {
        if (message == R.string.barcode_lookup_unreadable) compose.runOnIdle { unreadable = true }
        openScan()
        confirmPhoto()
        compose.waitUntil(10_000) {
          photo.state.value.confirmationFailed && !photo.state.value.busy
        }
        compose.onAllNodesWithText(compose.activity.getString(message)).onFirst().assertExists()
        compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
        compose.onNodeWithText("Scanned milk").assertExists()
      }
      compose.onNodeWithTag(StagingTestTags.ADD).performClick()
      compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextInput("Manual bread")
      compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
      compose.onNodeWithText("Manual bread").assertExists()
      compose.onNodeWithText("Scanned milk").assertExists()
      assertEquals(4, lookups)
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
      compose.onNodeWithText("Manual bread").assertExists()
      compose.onNodeWithText("Scanned milk").assertExists()
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
      File(instrumentation.targetContext.getExternalFilesDir(null), "barcode-purchase.png")
          .outputStream()
          .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
      screenshot.recycle()
    } finally {
      compose.runOnIdle { owners.clear() }
      file.delete()
    }
  }
}
