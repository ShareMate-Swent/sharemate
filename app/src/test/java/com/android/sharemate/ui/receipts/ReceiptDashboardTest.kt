// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptDashboardTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun emptyMessageMatchesSelectedFilter() {
    compose.setContent {
      MaterialTheme { ReceiptDashboard(emptyList(), false, false, {}, {}, {}, {}, null) }
    }
    compose.onNodeWithText("No receipts yet").assertIsDisplayed()
    compose.onNodeWithTag("receipt_filter_1").performClick()
    compose.onNodeWithText("No saved receipts yet").assertIsDisplayed()
    compose.onNodeWithText("No receipts yet").assertDoesNotExist()
    compose.onNodeWithTag("receipt_filter_2").performClick()
    compose.onNodeWithText("No drafts yet").assertIsDisplayed()
    compose.onNodeWithText("No saved receipts yet").assertDoesNotExist()
    compose.onNodeWithTag("receipt_filter_0").performClick()
    compose.onNodeWithText("No receipts yet").assertIsDisplayed()
  }

  @Test
  fun balanceShowsDebtCreditAndZero() {
    val balance = mutableStateOf(0L)
    compose.setContent {
      MaterialTheme {
        ReceiptDashboard(emptyList(), true, false, {}, {}, {}, {}, null, balance.value)
      }
    }
    compose.onNodeWithText("You owe 0.00 CHF").assertIsDisplayed()
    compose.onNodeWithText("Shared balances").assertDoesNotExist()
    compose.onNodeWithText("All settled up").assertDoesNotExist()
    compose.runOnIdle { balance.value = -300L }
    compose.onNodeWithText("You owe 3.00 CHF").assertIsDisplayed()
    compose.runOnIdle { balance.value = 400L }
    compose.onNodeWithText("You’re owed 4.00 CHF").assertIsDisplayed()
    compose.onNodeWithText("You owe 0.00 CHF").assertDoesNotExist()
    compose.runOnIdle { balance.value = 0L }
    compose.onNodeWithText("You owe 0.00 CHF").assertIsDisplayed()
  }

  @Test
  fun pendingSyncShowsOneMessageAndRetryAndClearsOnConfirmation() {
    val phase = mutableStateOf(InventorySavePhase.PENDING)
    var retries = 0
    compose.setContent {
      MaterialTheme { ReceiptSyncFeedback(null, phase.value, false) { retries++ } }
    }
    compose.onNodeWithText("Sync pending").assertIsDisplayed()
    compose.onAllNodesWithTag(InventorySaveTestTags.STATUS).assertCountEquals(1)
    compose.onNodeWithText("Retry").performClick()
    assertEquals(1, retries)
    compose.runOnIdle { phase.value = InventorySavePhase.SUBMITTING }
    compose.onNodeWithText("Saving…").assertIsDisplayed()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    compose.runOnIdle { phase.value = InventorySavePhase.FAILED }
    compose.onNodeWithText("Could not save changes").assertIsDisplayed()
    compose.runOnIdle { phase.value = InventorySavePhase.CONFIRMED }
    compose.onNodeWithTag(InventorySaveTestTags.STATUS).assertDoesNotExist()
  }

  private fun receipt(index: Int, draft: Boolean = true): RetainedReceipt {
    val id = UUID.randomUUID().toString()
    return RetainedReceipt(
        InventorySubmission(
            id, "owner", "home", listOf(StagedItem("row", "Item $index", 1, 100)), draft),
        ReceiptDetails(id, "owner", "home", "", 1700000000000L - index, title = "Item $index"),
        if (draft) InventorySavePhase.READY else InventorySavePhase.CONFIRMED)
  }

  @Test
  fun cardsUseReceiptTitlesAndDoNotExposeRedundantActions() {
    val receipt = receipt(0).let { it.copy(details = it.details?.copy(title = "Courses Migros")) }
    compose.setContent {
      MaterialTheme { ReceiptDashboard(listOf(receipt), false, false, {}, {}, {}, {}, {}) }
    }
    compose.onNodeWithText("Courses Migros").assertIsDisplayed()
    compose.onNodeWithText("Item 0").assertDoesNotExist()
    compose.onNodeWithText("View receipt").assertDoesNotExist()
    compose.onNodeWithText("Review items").assertDoesNotExist()
    compose.onNodeWithText("Scan purchase receipt").assertDoesNotExist()
    compose.onNodeWithContentDescription("More receipt actions").assertDoesNotExist()
  }

  @Test
  fun viewMoreAddsTenUntilAllReceiptsAreVisible() {
    val receipts = (0 until 25).map { receipt(it) }
    compose.setContent {
      MaterialTheme { ReceiptDashboard(receipts, false, false, {}, {}, {}, {}, null) }
    }
    val list = compose.onNodeWithTag("receipt_history_list")
    list.performScrollToNode(hasTestTag("receipt_view_more"))
    compose.onNodeWithText("Item 9").assertExists()
    compose.onNodeWithText("Item 10").assertDoesNotExist()
    compose.onNodeWithTag("receipt_view_more").performClick()
    list.performScrollToNode(hasTestTag("receipt_view_more"))
    compose.onNodeWithText("Item 19").assertExists()
    compose.onNodeWithText("Item 20").assertDoesNotExist()
    compose.onNodeWithTag("receipt_view_more").performClick()
    list.performScrollToNode(hasText("Item 24"))
    compose.onNodeWithText("Item 24").assertIsDisplayed()
    compose.onNodeWithTag("receipt_view_more").assertDoesNotExist()
    compose.onNodeWithText("View less").assertDoesNotExist()
  }

  @Test
  fun summaryBoxesFilterAndCardsOpenTheirOwnReceipt() {
    val receipts = listOf(receipt(0), receipt(1, false))
    var opened: String? = null
    compose.setContent {
      MaterialTheme { ReceiptDashboard(receipts, false, false, {}, {}, {}, { opened = it }, null) }
    }
    compose.onNodeWithTag("receipt_filter_1").performClick()
    compose.onNodeWithText("Item 0").assertDoesNotExist()
    compose.onNodeWithText("Item 1").performClick()
    assertEquals(receipts[1].submission.id, opened)
    compose.onNodeWithTag("receipt_filter_2").performClick()
    compose.onNodeWithText("Item 1").assertDoesNotExist()
    compose.onNodeWithText("Item 0").assertIsDisplayed()
  }

  @Test
  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  fun missingMetadataAndPendingCardsRemainReadableAndActionsWork() {
    val missing = receipt(0, false).copy(details = null, phase = InventorySavePhase.PENDING)
    val failed =
        receipt(1, false).let {
          it.copy(details = it.details?.copy(title = ""), phase = InventorySavePhase.FAILED)
        }
    val ready = receipt(2).let { it.copy(details = it.details?.copy(title = "Draft title")) }
    var scans = 0
    var balances = 0
    var retries = 0
    compose.setContent {
      MaterialTheme {
        ReceiptDashboard(
            listOf(missing, failed, ready),
            true,
            true,
            { scans++ },
            { balances++ },
            { retries++ },
            {},
            null)
      }
    }
    compose.onNodeWithText("Balance unavailable").performClick()
    compose
        .onNodeWithText(compose.activity.getString(com.android.sharemate.R.string.scan_receipt))
        .performClick()
    compose
        .onNodeWithText(
            compose.activity.getString(com.android.sharemate.R.string.receipt_edit_failed))
        .performClick()
    assertEquals(1, scans)
    assertEquals(1, balances)
    assertEquals(1, retries)
    val list = compose.onNodeWithTag("receipt_history_list")
    list.performScrollToNode(hasTestTag("receipt_card_${missing.submission.id}"))
    compose.onNodeWithTag("receipt_card_${missing.submission.id}").assertIsDisplayed()
    assertTimelineIsDrawn()
    list.performScrollToNode(hasTestTag("receipt_card_${failed.submission.id}"))
    compose.onNodeWithTag("receipt_card_${failed.submission.id}").assertIsDisplayed()
    assertTimelineIsDrawn()
  }

  @Test
  fun progressHeaderAndExitDialogKeepNavigationAndSaveActionsDistinct() {
    val busy = mutableStateOf(false)
    val failed = mutableStateOf(false)
    val showDialog = mutableStateOf(false)
    val completed = mutableStateOf(1)
    var backs = 0
    var saves = 0
    var discards = 0
    compose.setContent {
      MaterialTheme {
        androidx.compose.foundation.layout.Column {
          ReceiptHeader(
              "Review items", { backs++ }, actions = { androidx.compose.material3.Text("Menu") })
          ReceiptProgress(completed.value)
          ReceiptSyncFeedback(
              InventorySaveState(InventorySavePhase.CONFIRMED),
              InventorySavePhase.PENDING,
              failed.value) {}
        }
        if (showDialog.value)
            ReceiptExitDialog(
                busy.value,
                failed.value,
                onSaveDraft = { saves++ },
                onDiscard = { discards++ },
                onDismiss = { showDialog.value = false })
      }
    }
    compose.onNodeWithTag("receipt_back").performClick()
    assertEquals(1, backs)
    compose.onNodeWithText("Menu").assertIsDisplayed()
    for (step in 0..3) {
      compose.runOnIdle { completed.value = step }
      compose
          .onNode(
              SemanticsMatcher.expectValue(
                  androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                  androidx.compose.ui.semantics.ProgressBarRangeInfo(step.toFloat(), 0f..3f, 2)))
          .assertExists()
    }
    compose.onNodeWithTag(InventorySaveTestTags.STATUS).assertDoesNotExist()
    compose.runOnIdle { showDialog.value = true }
    compose.onNodeWithText("Save as draft").performClick()
    compose.onNodeWithText("Discard").performClick()
    assertEquals(1, saves)
    assertEquals(1, discards)
    compose.runOnIdle {
      busy.value = true
      failed.value = true
    }
    compose.onNodeWithTag("receipt_save_draft").assertIsNotEnabled()
    compose.onNodeWithTag("receipt_discard").assertIsNotEnabled()
    compose.onNodeWithText("Could not finish. Try again.").assertIsDisplayed()
    compose.runOnIdle {
      busy.value = false
      showDialog.value = false
    }
    compose.onNodeWithText("Could not save changes").assertIsDisplayed()
  }

  private fun assertTimelineIsDrawn() {
    compose.runOnIdle {
      val view = compose.activity.window.decorView
      val bitmap =
          android.graphics.Bitmap.createBitmap(
              view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
      try {
        view.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        org.junit.Assert.assertTrue(pixels.any { it == android.graphics.Color.rgb(110, 75, 195) })
      } finally {
        bitmap.recycle()
      }
    }
  }
}
