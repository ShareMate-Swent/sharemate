// Co-authored-by: Codex
package com.android.sharemate.model.receipt

/** Reads reviewable products from a local image URI without exposing Android APIs to callers. */
fun interface ReceiptReader {
  /**
   * Returns extracted products with row totals (null when ambiguous), or an empty list if
   * unreadable; failures propagate to the caller.
   */
  suspend fun read(uri: String): List<ReceiptItem>
}
