// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.InventorySubmissionStore

/** Finish durable uploads before membership is removed, so eviction cannot lose a new scan. */
class ReceiptDepartureSync(
    private val store: InventorySubmissionStore,
    private val synchronize: suspend (InventorySubmission) -> Boolean,
) {
  suspend fun prepare(owner: String, home: String) {
    try {
      kotlinx.coroutines.withTimeout(UPLOAD_TIMEOUT) {
        for (receipt in store.list(owner, home)) {
          check(synchronize(receipt)) { "Receipt synchronization must finish before leaving" }
        }
      }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
      throw IllegalStateException("Receipt upload timed out; retry leaving", timeout)
    }
  }

  companion object {
    private const val UPLOAD_TIMEOUT = 60_000L

    /** Uses the same durable submission protocol as background receipt synchronization. */
    fun create(
        context: android.content.Context,
        items: com.android.sharemate.model.item.ItemRepository
    ): ReceiptDepartureSync {
      val store = com.android.sharemate.model.staging.AndroidInventorySubmissionStore(context)
      val cloud = FirebaseReceiptCloudRepository(context)
      val sync =
          com.android.sharemate.model.staging.ReceiptSynchronization(
              store,
              items,
              com.android.sharemate.model.finance.FirebaseExpenseSubmissionRepository(
                  com.google.firebase.functions.FirebaseFunctions.getInstance("europe-west6"))) {}
      return ReceiptDepartureSync(store) { receipt ->
        val owner = receipt.ownerId
        val home = receipt.householdId
        if (!sync.synchronize(owner, home, receipt.id, owner, false)) false
        else {
          val latest = checkNotNull(store.loadById(owner, home, receipt.id))
          check(
              latest.draft ||
                  latest.expenseProgress ==
                      com.android.sharemate.model.item.ExpenseProgress.CONFIRMED)
          cloud.upload(
              ReceiptCloudEntry(latest, checkNotNull(store.details(owner, home, receipt.id))))
          true
        }
      }
    }
  }
}
