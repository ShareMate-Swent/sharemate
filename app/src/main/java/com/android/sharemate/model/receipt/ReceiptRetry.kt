// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

internal const val RECEIPT_SYNC_TAG = "ReceiptSync"
internal const val RECEIPT_INITIAL_RETRY_MILLIS = 1_000L
internal const val RECEIPT_MAX_RETRY_MILLIS = 30_000L
internal const val RECEIPT_PHOTO_MAX_RETRY_MILLIS = 300_000L

/** Doubles a receipt retry delay up to the caller's operation-specific limit. */
internal fun nextReceiptRetry(delay: Long, limit: Long): Long =
    if (delay >= limit / 2) limit else delay * 2
