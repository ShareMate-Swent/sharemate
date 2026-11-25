// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.staging.ReceiptDetails

/** Protects local edits from stale server snapshots and unacknowledged writes. */
class ReceiptRemoteMergePolicy {
  /**
   * Returns whether [entry] may replace [local] and [localDetails]. [locallyDeleted] tombstones and
   * [correctionPending] journal entries always win. Equal revisions preserve local titles.
   */
  fun shouldApplyRemote(
      local: InventorySubmission?,
      localDetails: ReceiptDetails?,
      entry: ReceiptCloudEntry,
      locallyDeleted: Boolean,
      correctionPending: Boolean,
  ): Boolean {
    if (locallyDeleted || correctionPending || entry.details.revision < 0) return false
    if (local == null) return true
    if (entry.submission.draft && !local.draft) return false
    if (local.draft || local.expenseProgress != ExpenseProgress.CONFIRMED) return false
    return localDetails == null || entry.details.revision > localDetails.revision
  }
}
