// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

/** Exit choices distinguish a new scan from editing an existing receipt. */
internal enum class ReceiptExitChoice {
  LEAVE,
  SAVE_DRAFT,
  KEEP_CHANGES
}

/** Existing drafts are preserved when edits are discarded; only new scans can be abandoned. */
internal fun receiptExitChoice(
    fresh: Boolean,
    draft: Boolean,
    hasPhoto: Boolean,
    changed: Boolean
): ReceiptExitChoice =
    when {
      fresh && draft && hasPhoto -> ReceiptExitChoice.SAVE_DRAFT
      changed -> ReceiptExitChoice.KEEP_CHANGES
      else -> ReceiptExitChoice.LEAVE
    }
