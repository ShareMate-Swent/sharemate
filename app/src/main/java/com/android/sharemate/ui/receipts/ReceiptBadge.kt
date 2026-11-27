// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.R
import com.android.sharemate.model.item.ExpenseProgress

/** Unknown connectivity cannot prove that a receipt is offline or was sent to the server. */
internal fun receiptBadgeLabel(receipt: RetainedReceipt, online: Boolean?): Int =
    when {
      receipt.phase == InventorySavePhase.CONFIRMED -> R.string.receipt_status_saved
      receipt.submission.expenseProgress == ExpenseProgress.REJECTED ->
          R.string.receipt_badge_rejected
      receipt.phase == InventorySavePhase.FAILED -> R.string.receipt_badge_failed
      receipt.phase == InventorySavePhase.READY -> R.string.receipt_status_draft
      receipt.phase == InventorySavePhase.RECOVERY_FAILED -> R.string.receipt_badge_unverified
      receipt.phase == InventorySavePhase.LOADING -> R.string.receipt_badge_checking
      online == false -> R.string.receipt_badge_offline
      else -> R.string.receipt_badge_pending
    }
