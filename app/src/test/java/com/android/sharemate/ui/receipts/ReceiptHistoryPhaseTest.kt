// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptHistoryPhaseTest {
  private val id = UUID.randomUUID().toString()
  private val rows = listOf(StagedItem("milk", "Milk", 1, 100))
  private val savedDetails = ReceiptDetails(id, "owner", "home", "", 1, savedAtMillis = 2)

  @Test
  fun acknowledgedInventoryOnlyCorrectionStaysConfirmedWithoutSavedMetadata() {
    val receipt =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows.map { it.copy(includedInExpense = false) },
            inventoryConfirmed = true)
    assertEquals(
        InventorySavePhase.CONFIRMED,
        receiptHistoryPhase(receipt, null, InventorySavePhase.PENDING, false))
    assertEquals(InventorySavePhase.PENDING, receiptHistoryPhase(receipt, null, null, true))
  }

  @Test
  fun pendingCorrectionsAndUnconfirmedExpensesOverrideOldSavedMetadata() {
    for (progress in ExpenseProgress.entries) {
      val receipt =
          InventorySubmission(
              id,
              "owner",
              "home",
              rows,
              expense = SharedExpense(id, "home", "owner", 100, mapOf("owner" to 100L)),
              inventoryConfirmed = true,
              expenseProgress = progress)
      assertEquals(
          InventorySavePhase.PENDING,
          receiptHistoryPhase(receipt, savedDetails, InventorySavePhase.CONFIRMED, true))
      assertEquals(
          if (progress == ExpenseProgress.CONFIRMED) InventorySavePhase.CONFIRMED
          else InventorySavePhase.PENDING,
          receiptHistoryPhase(receipt, savedDetails, InventorySavePhase.CONFIRMED, false))
    }
  }

  @Test
  fun legacyReceiptsRetainObservedStateAndUseMetadataOnlyWithoutAnObservation() {
    val draft = InventorySubmission(id, "owner", "home", rows, draft = true)
    val saved = InventorySubmission(id, "owner", "home", rows)
    assertEquals(InventorySavePhase.READY, receiptHistoryPhase(draft, savedDetails, null, false))
    assertEquals(InventorySavePhase.PENDING, receiptHistoryPhase(draft, savedDetails, null, true))
    assertEquals(InventorySavePhase.PENDING, receiptHistoryPhase(saved, null, null, false))
    assertEquals(
        InventorySavePhase.CONFIRMED, receiptHistoryPhase(saved, savedDetails, null, false))
    assertEquals(
        InventorySavePhase.FAILED,
        receiptHistoryPhase(saved, savedDetails, InventorySavePhase.FAILED, false))
  }
}
