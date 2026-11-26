// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.InventorySubmissionStore

/** Finish durable uploads before membership is removed, so eviction cannot lose a new scan. */
class ReceiptDepartureSync(
    private val store: InventorySubmissionStore,
    private val synchronize: suspend (InventorySubmission, String) -> Boolean,
) {
  /**
   * Flushes the departing viewer's own receipts and shared edits; failure leaves departure blocked.
   */
  suspend fun prepare(viewer: String, home: String) {
    try {
      kotlinx.coroutines.withTimeout(UPLOAD_TIMEOUT) {
        val scoped = store.forViewer(viewer)
        for (receipt in scoped.list(viewer, home) + scoped.sharedReceipts(viewer, home)) {
          check(synchronize(receipt, viewer)) {
            "Receipt synchronization must finish before leaving"
          }
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
    ): ReceiptDepartureSync =
        create(
            com.android.sharemate.model.staging.AndroidInventorySubmissionStore(context),
            items,
            com.android.sharemate.model.finance.FirebaseExpenseSubmissionRepository(
                com.google.firebase.functions.FirebaseFunctions.getInstance("europe-west6")),
            FirebaseReceiptCloudRepository(context)::upload)

    internal fun create(
        store: InventorySubmissionStore,
        items: com.android.sharemate.model.item.ItemRepository,
        expenses: com.android.sharemate.model.finance.ExpenseSubmissionRepository,
        upload: suspend (ReceiptCloudEntry) -> Unit,
    ): ReceiptDepartureSync {
      return ReceiptDepartureSync(store) { receipt, actor ->
        val scoped = store.forViewer(actor)
        val sync =
            com.android.sharemate.model.staging.ReceiptSynchronization(scoped, items, expenses) {}
        val owner = receipt.ownerId
        val home = receipt.householdId
        if (!sync.synchronize(owner, home, receipt.id, actor, false)) false
        else if (actor != owner) true
        else {
          val latest = checkNotNull(scoped.loadById(owner, home, receipt.id))
          check(
              latest.draft ||
                  latest.expenseProgress ==
                      com.android.sharemate.model.item.ExpenseProgress.CONFIRMED)
          upload(ReceiptCloudEntry(latest, checkNotNull(scoped.details(owner, home, receipt.id))))
          true
        }
      }
    }
  }
}
