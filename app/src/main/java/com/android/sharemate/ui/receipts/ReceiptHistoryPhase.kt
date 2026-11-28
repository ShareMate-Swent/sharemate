// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.ReceiptDetails

/**
 * Pending corrections override server confirmation; legacy inventory retains its observed phase.
 */
internal fun receiptHistoryPhase(
    submission: InventorySubmission,
    details: ReceiptDetails?,
    prior: InventorySavePhase?,
    correctionPending: Boolean,
): InventorySavePhase =
    when {
      correctionPending -> InventorySavePhase.PENDING
      submission.expense != null ->
          if (submission.expenseProgress == ExpenseProgress.CONFIRMED) InventorySavePhase.CONFIRMED
          else InventorySavePhase.PENDING
      submission.inventoryConfirmed -> InventorySavePhase.CONFIRMED
      prior != null -> prior
      submission.draft -> InventorySavePhase.READY
      details?.savedAtMillis != null -> InventorySavePhase.CONFIRMED
      else -> InventorySavePhase.PENDING
    }
