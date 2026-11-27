// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.ItemSharing

/** Durable, immutable correction; retries use the same identity and expected server version. */
data class ReceiptCorrection(
    val operationId: String,
    val original: InventorySubmission,
    val replacement: InventorySubmission,
    val title: String,
    val originalTitle: String = "",
    val originalRevision: Long = 0,
) {
  init {
    require(originalRevision >= 0 && originalRevision < Long.MAX_VALUE)
    require(
        original.id == replacement.id &&
            original.ownerId == replacement.ownerId &&
            original.householdId == replacement.householdId)
    require(!original.draft && !replacement.draft)
    require(
        replacement.expense != null ||
            (original.expense == null &&
                original.sharing == ItemSharing.PERSONAL &&
                replacement.sharing == ItemSharing.PERSONAL))
  }

  /** Private receipts have no shared expense to acknowledge. */
  val confirmedReplacement: InventorySubmission
    get() =
        if (replacement.expense == null) replacement
        else replacement.withProgress(true, ExpenseProgress.CONFIRMED)
}
