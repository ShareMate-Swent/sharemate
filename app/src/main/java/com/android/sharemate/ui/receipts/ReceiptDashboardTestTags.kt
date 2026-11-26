// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

/** Stable semantics shared by the dashboard, exit dialog and their tests. */
object ReceiptDashboardTestTags {
  /** Receipt timeline and its pagination action. */
  const val HISTORY_LIST = "receipt_history_list"
  const val VIEW_MORE = "receipt_view_more"
  /** Exit dialog actions and secondary-page navigation. */
  const val SAVE_DRAFT = "receipt_save_draft"
  const val DISCARD = "receipt_discard"
  const val BACK = "receipt_back"
  /** Selects the all, saved or draft summary by index. */
  fun filter(index: Int) = "receipt_filter_$index"
  /** Opens the retained receipt with this identity. */
  fun card(id: String) = "receipt_card_$id"
}
