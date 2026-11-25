// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptRetryTest {
  @Test
  fun doublesUntilTheOperationLimitWithoutOverflow() {
    assertEquals(
        RECEIPT_INITIAL_RETRY_MILLIS * 2,
        nextReceiptRetry(RECEIPT_INITIAL_RETRY_MILLIS, RECEIPT_MAX_RETRY_MILLIS))
    for (limit in listOf(RECEIPT_MAX_RETRY_MILLIS, RECEIPT_PHOTO_MAX_RETRY_MILLIS)) {
      assertEquals(limit, nextReceiptRetry(limit - 1, limit))
      assertEquals(limit, nextReceiptRetry(Long.MAX_VALUE, limit))
    }
  }
}
