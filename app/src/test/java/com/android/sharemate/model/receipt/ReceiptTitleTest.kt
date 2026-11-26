// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptTitleTest {
  @Test
  fun trimsBeforeLimitingTitlesAtTheServerBoundary() {
    for (length in listOf(79, 80, 81)) {
      assertEquals(
          "x".repeat(minOf(length, 80)), normalizeReceiptTitle("  ${"x".repeat(length)}  "))
    }
    assertEquals("", normalizeReceiptTitle(" \t\n "))
    assertEquals("Weekly groceries", normalizeReceiptTitle("  Weekly groceries  "))
  }
}
