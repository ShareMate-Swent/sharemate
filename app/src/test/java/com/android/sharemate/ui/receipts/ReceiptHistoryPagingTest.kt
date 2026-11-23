// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptHistoryPagingTest {
  @Test
  fun growsInTenReceiptBatches() {
    assertEquals(20, nextReceiptLimit(10, 50))
    assertEquals(30, nextReceiptLimit(20, 50))
    assertEquals(50, nextReceiptLimit(40, 50))
  }

  @Test
  fun finalBatchStopsAtAvailableCount() {
    assertEquals(23, nextReceiptLimit(20, 23))
    assertEquals(23, nextReceiptLimit(23, 23))
  }
}
