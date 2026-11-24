// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.item.InventorySubmission

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
    require(!original.draft && !replacement.draft && replacement.expense != null)
  }
}
