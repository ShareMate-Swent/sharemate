// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.image.BarcodePhotoStore
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReceiptScanRecreationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun pendingReadNavigatesWithItsProductsAfterActivityRecreation() {
    val firstActivity = compose.activity
    val file = File.createTempFile("receipt-recreation", ".png", firstActivity.cacheDir)
    val result = CompletableDeferred<List<ReceiptItem>>()
    val started = CompletableDeferred<Unit>()
    var reads = 0
    lateinit var photo: BarcodePhotoViewModel
    try {
      val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
      bitmap.recycle()
      val store =
          object : BarcodePhotoStore {
            override suspend fun createCapture() = error("Unexpected camera call")

            override suspend fun importImage(uri: String) = Uri.fromFile(file).toString()

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          }
      val reader = ReceiptReader {
        reads++
        started.complete(Unit)
        result.await()
      }
      val content: @Composable () -> Unit = {
        photo =
            viewModel(
                factory =
                    viewModelFactory {
                      initializer { BarcodePhotoViewModel(store, SavedStateHandle()) }
                    })
        MaterialTheme { ReceiptScanFlow(photo, reader = reader) }
      }
      compose.setContent(content)
      compose.onNodeWithText(firstActivity.getString(R.string.scan_receipt)).performClick()
      compose.runOnIdle { photo.select(Uri.fromFile(file).toString()) }
      compose.waitUntil(WAIT_TIMEOUT_MS) {
        photo.state.value.uri != null && !photo.state.value.busy
      }
      compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(WAIT_TIMEOUT_MS) { started.isCompleted }
      val firstPhoto = photo

      // Recreate while the callback is suspended, before any products or navigation exist.
      compose.activityRule.scenario.recreate()
      compose.activityRule.scenario.onActivity {
        assertNotSame(firstActivity, it)
        it.setContent(content = content)
        it.window.decorView.requestLayout()
      }
      compose.waitForIdle()
      compose.runOnIdle {
        assertSame(firstPhoto, photo)
        result.complete(listOf(ReceiptItem("Lait", 240), ReceiptItem("Pain", 310)))
      }
      compose.waitUntil(WAIT_TIMEOUT_MS) { photo.state.value.confirmed && !photo.state.value.busy }
      compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).assertIsDisplayed()
      compose.onNodeWithText("Lait").assertIsDisplayed()
      compose.onNodeWithText("Pain").assertIsDisplayed()
      compose
          .onNodeWithText(compose.activity.getString(R.string.staging_price, "2.40"))
          .assertIsDisplayed()
      compose
          .onNodeWithText(compose.activity.getString(R.string.staging_price, "3.10"))
          .assertIsDisplayed()
      assertEquals(1, reads)
    } finally {
      result.cancel()
      file.delete()
    }
  }

  private companion object {
    const val IMAGE_SIZE = 8
    const val PNG_QUALITY = 100
    const val WAIT_TIMEOUT_MS = 10_000L
  }
}
