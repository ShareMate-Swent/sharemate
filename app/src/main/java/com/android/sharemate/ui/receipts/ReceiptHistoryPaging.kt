// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

internal const val RECEIPT_PAGE_SIZE = 10

internal fun nextReceiptLimit(current: Int, total: Int): Int =
    (current.toLong() + RECEIPT_PAGE_SIZE).coerceAtMost(total.toLong()).toInt()
