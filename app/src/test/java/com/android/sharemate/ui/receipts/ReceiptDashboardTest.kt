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
}
