// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.*
import org.junit.Test

class ReceiptRemoteMergePolicyTest {
  private val policy = ReceiptRemoteMergePolicy()
  private val id = "00000000-0000-0000-0000-000000000001"
  private val rows = listOf(StagedItem("row", "Milk", 1, 100))
  private val saved =
      InventorySubmission(
          id,
          "owner",
          "home",
          rows,
          expense = SharedExpense(id, "home", "owner", 100, mapOf("owner" to 100L)),
          inventoryConfirmed = true,
          expenseProgress = ExpenseProgress.CONFIRMED)
  private val details =
      ReceiptDetails(id, "owner", "home", "photo", 1, title = "Local", revision = 2)

  @Test
  fun newerFinancialRevisionCanFollowAnAcknowledgedInventoryOnlyCorrection() {
    val inventoryOnly =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows.map { it.copy(includedInExpense = false) },
            inventoryConfirmed = true)
    val entry = ReceiptCloudEntry(saved, details.copy(revision = 3))
    assertTrue(policy.shouldApplyRemote(inventoryOnly, details, entry, false, false))
    assertFalse(
        policy.shouldApplyRemote(inventoryOnly.withProgress(false), details, entry, false, false))
  }

  @Test
  fun onlyNewerRevisionMayReplaceExistingLocalContent() {
    for (revision in listOf(1L, 2L, 3L)) {
      val entry = ReceiptCloudEntry(saved, details.copy(title = "Remote", revision = revision))
      assertEquals(
          "revision $revision",
          revision > 2,
          policy.shouldApplyRemote(saved, details, entry, false, false))
    }
  }

  @Test
  fun localDraftsUnconfirmedWritesCorrectionsAndDeletionBlockRemoteReplacement() {
    val entry = ReceiptCloudEntry(saved, details.copy(revision = 3))
    val draft = InventorySubmission(id, "owner", "home", rows, draft = true)
    val pending = saved.withProgress(false, ExpenseProgress.QUEUED)
    for (local in listOf(draft, pending)) {
      assertFalse(policy.shouldApplyRemote(local, details, entry, false, false))
    }
    for (local in listOf(null, saved)) {
      assertFalse(policy.shouldApplyRemote(local, details, entry, true, false))
      assertFalse(policy.shouldApplyRemote(local, details, entry, false, true))
    }
    assertFalse(
        policy.shouldApplyRemote(
            null, null, entry.copy(details = details.copy(revision = -1)), false, false))
    assertTrue(policy.shouldApplyRemote(null, null, entry, false, false))
    assertTrue(policy.shouldApplyRemote(saved, null, entry, false, false))
    assertFalse(policy.shouldApplyRemote(saved, null, entry.copy(submission = draft), false, false))
  }
}
