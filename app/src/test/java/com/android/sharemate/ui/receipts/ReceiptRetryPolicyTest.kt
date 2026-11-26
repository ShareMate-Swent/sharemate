// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptRetryPolicyTest {
  @Test
  fun historyFailureDoesNotSwallowPendingPurchaseRetry() {
    val actions = mutableListOf<String>()
    retryReceiptSave(
        true, InventorySavePhase.PENDING, { actions += "reload" }, { actions += "retry" })
    assertEquals(listOf("reload", "retry"), actions)
  }

  @Test
  fun retryNeverSubmitsDraftsOrReplaysConfirmedPurchases() {
    for (phase in
        listOf(
            InventorySavePhase.READY,
            InventorySavePhase.CONFIRMED,
            InventorySavePhase.SUBMITTING,
            InventorySavePhase.LOADING)) {
      var reloads = 0
      retryReceiptSave(true, phase, { reloads++ }, { error("Unexpected submission for $phase") })
      assertEquals(1, reloads)
    }
  }

  @Test
  fun failedAndUncertainSubmissionsCanRetryWithoutReloadingHealthyHistory() {
    for (phase in listOf(InventorySavePhase.FAILED, InventorySavePhase.RECOVERY_FAILED)) {
      var retries = 0
      retryReceiptSave(false, phase, { error("Unexpected history reload") }, { retries++ })
      assertEquals(1, retries)
    }
  }
}
