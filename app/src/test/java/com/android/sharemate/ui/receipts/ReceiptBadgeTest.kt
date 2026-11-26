// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.R
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptBadgeTest {
  private val id = "00000000-0000-0000-0000-000000000001"

  private fun receipt(phase: InventorySavePhase) =
      RetainedReceipt(
          InventorySubmission(id, "owner", "home", listOf(StagedItem("a", "Apple", 1, 100))),
          null,
          phase)

  @Test
  fun pendingDistinguishesOfflineFromUnknownOrOnlineWithoutClaimingDelivery() {
    val pending = receipt(InventorySavePhase.PENDING)
    assertEquals(R.string.receipt_badge_offline, receiptBadgeLabel(pending, false))
    assertEquals(R.string.receipt_badge_pending, receiptBadgeLabel(pending, true))
    assertEquals(R.string.receipt_badge_pending, receiptBadgeLabel(pending, null))
  }

  @Test
  fun offlineDoesNotHideSavedFailedOrDraftState() {
    for ((phase, label) in
        listOf(
            InventorySavePhase.CONFIRMED to R.string.receipt_status_saved,
            InventorySavePhase.READY to R.string.receipt_status_draft,
            InventorySavePhase.FAILED to R.string.receipt_badge_failed,
            InventorySavePhase.RECOVERY_FAILED to R.string.receipt_badge_unverified,
            InventorySavePhase.LOADING to R.string.receipt_badge_checking)) {
      assertEquals(label, receiptBadgeLabel(receipt(phase), false))
    }
  }

  @Test
  fun onlyDefiniteExpenseRejectionIsLabelledRefused() {
    val submission =
        InventorySubmission(
            id,
            "owner",
            "home",
            listOf(StagedItem("a", "Apple", 1, 100)),
            expense = SharedExpense(id, "home", "owner", 100, mapOf("owner" to 100L)),
            inventoryConfirmed = true,
            expenseProgress = ExpenseProgress.REJECTED)
    assertEquals(
        R.string.receipt_badge_rejected,
        receiptBadgeLabel(RetainedReceipt(submission, null, InventorySavePhase.PENDING), false))
  }
}
