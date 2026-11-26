// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptExitPolicyTest {
  @Test
  fun existingReceiptsOnlyPromptForChangesRegardlessOfStatus() {
    for (draft in listOf(true, false)) {
      assertEquals(ReceiptExitChoice.LEAVE, receiptExitChoice(false, draft, true, false))
      assertEquals(ReceiptExitChoice.KEEP_CHANGES, receiptExitChoice(false, draft, true, true))
    }
  }

  @Test
  fun freshScanOffersDraftOnlyAfterPhotoAndNeverAfterSaving() {
    assertEquals(ReceiptExitChoice.LEAVE, receiptExitChoice(true, true, false, false))
    assertEquals(ReceiptExitChoice.SAVE_DRAFT, receiptExitChoice(true, true, true, false))
    assertEquals(ReceiptExitChoice.SAVE_DRAFT, receiptExitChoice(true, true, true, true))
    assertEquals(ReceiptExitChoice.LEAVE, receiptExitChoice(true, false, true, false))
  }
}
