// Co-authored-by: OpenAI Codex <noreply@openai.com>
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
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.receipt.AndroidReceiptPhotoStore
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReceiptScanRecreationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun pendingReadNavigatesWithItsProductsAfterActivityRecreation() =
      assertReviewAfterRecreation(useHistory = false)

  @Test
  fun historyScanOpensTheReviewOnceAcrossActivityRecreations() =
      assertReviewAfterRecreation(useHistory = true)

  private fun assertReviewAfterRecreation(useHistory: Boolean) {
    val firstActivity = compose.activity
    val file = File.createTempFile("receipt-recreation", ".png", firstActivity.cacheDir)
    val result = CompletableDeferred<List<ReceiptItem>>()
    val started = CompletableDeferred<Unit>()
    var reads = 0
    lateinit var photo: PhotoPickerViewModel
    val itemRepository = mock(ItemRepository::class.java)
    val context = firstActivity.applicationContext
    try {
      val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
      bitmap.recycle()
      val store =
          object : TemporaryPhotoStore {
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
                      initializer { PhotoPickerViewModel(store, SavedStateHandle()) }
                    })
        MaterialTheme {
          if (useHistory) {
            val history: ReceiptHistoryViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                          initializer {
                            val historyStore =
                                AndroidInventorySubmissionStore(context, Dispatchers.Main.immediate)
                            ReceiptHistoryViewModel(
                                itemRepository,
                                historyStore,
                                AndroidReceiptPhotoStore(context, Dispatchers.Main.immediate),
                                "owner",
                                "home",
                                expenses = mock(ExpenseSubmissionRepository::class.java),
                                synchronization =
                                    ReceiptHistorySync(
                                        historyStore, EmptyReceiptCloud, "owner", "home"),
                                now = { 0L })
                          }
                        })
            ReceiptHistoryFlow(
                photo,
                reader,
                history,
                sharedHousehold = false,
                showBalances = false,
                onOpenBalances = {},
                onPurchase = null,
                itemRepository = itemRepository,
                expenseRepository = null,
                userId = "owner",
                householdId = "home",
                purchaseReview = null)
          } else {
            ReceiptScanFlow(photo, reader = reader)
          }
        }
      }
      fun recreateActivity() {
        val previous = compose.activity
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
          assertNotSame(previous, it)
          it.setContent(content = content)
          it.window.decorView.requestLayout()
        }
        compose.waitForIdle()
      }
      compose.setContent(content)
      compose.onNodeWithText(firstActivity.getString(R.string.scan_receipt)).performClick()
      compose.runOnIdle { photo.select(Uri.fromFile(file).toString()) }
      compose.waitUntil(WAIT_TIMEOUT_MS) {
        photo.state.value.selection?.uri != null && !photo.state.value.busy
      }
      compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(WAIT_TIMEOUT_MS) { started.isCompleted }
      val firstPhoto = photo

      // Recreate while the callback is suspended, before any products or navigation exist.
      recreateActivity()
      compose.runOnIdle {
        assertSame(firstPhoto, photo)
        result.complete(listOf(ReceiptItem("Lait", 240), ReceiptItem("Pain", 310)))
      }
      compose.waitUntil(WAIT_TIMEOUT_MS) { !photo.state.value.busy }
      assertTrue(
          "The picker should confirm the scan. State: ${photo.state.value}",
          photo.state.value.confirmed)
      compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).assertIsDisplayed()
      compose.onNodeWithText("Lait").assertIsDisplayed()
      compose.onNodeWithText("Pain").assertIsDisplayed()
      compose
          .onNodeWithText(compose.activity.getString(R.string.staging_price, "2.40"))
          .assertIsDisplayed()
      compose
          .onNodeWithText(compose.activity.getString(R.string.staging_price, "3.10"))
          .assertIsDisplayed()
      if (useHistory) {
        compose.onNodeWithTag(StagingTestTags.BACK).performClick()
        compose.onNodeWithTag(ReceiptDashboardTestTags.SAVE_DRAFT).performClick()
        compose.onNodeWithTag(ReceiptDashboardTestTags.HISTORY_LIST).assertIsDisplayed()
        recreateActivity()
        compose.onNodeWithTag(ReceiptDashboardTestTags.HISTORY_LIST).assertIsDisplayed()
        compose.onNodeWithTag(StagingTestTags.LIST).assertDoesNotExist()
      }
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
