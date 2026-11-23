// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.item.*
import kotlinx.coroutines.flow.first

/** Resumes journaled work without replaying inventory inserts or changing receipt ownership. */
internal class ReceiptSynchronization(
    private val store: InventorySubmissionStore,
    private val items: ItemRepository,
    private val expenses: ExpenseSubmissionRepository,
    private val upload: suspend (InventorySubmission) -> Unit,
) {
  /** False means the inventory has not yet been acknowledged and the job should retry. */
  suspend fun synchronize(
      owner: String,
      home: String,
      id: String,
      actor: String,
      delete: Boolean
  ): Boolean {
    if (delete) {
      expenses.deleteReceipt(home, id)
      return true
    }
    val receipt = store.loadById(owner, home, id) ?: return true
    store.pendingCorrection(owner, home, id)?.let { correction ->
      expenses.correctReceipt(correction)
      // A foreground coordinator may already have completed or replaced this operation.
      if (store.pendingCorrection(owner, home, id)?.operationId == correction.operationId) {
        store.save(correction.replacement.withProgress(true, ExpenseProgress.CONFIRMED))
        store.details(owner, home, id)?.let { store.saveDetails(it.copy(title = correction.title)) }
        store.clearCorrection(owner, home, id)
      }
      if (actor == owner) store.loadById(owner, home, id)?.let { upload(it) }
      return true
    }
    if (actor != owner) return true
    if (receipt.draft) {
      upload(receipt)
      return true
    }
    // Firestore's durable batch queue owns inventory delivery; never insert it a second time.
    val status = items.observeSubmission(receipt).first { it != InventorySubmissionStatus.PENDING }
    // A server-confirmed absence for an already finished receipt is terminal (for example a
    // removed receipt or a local demo). Never retry it forever or recreate its financial data.
    if (status == InventorySubmissionStatus.MISSING)
        return receipt.expenseProgress == ExpenseProgress.CONFIRMED
    val expense = receipt.expense ?: return true
    if (receipt.expenseProgress != ExpenseProgress.CONFIRMED) {
      expenses.recordExpense(expense)
      val latest = store.loadById(owner, home, id)
      if (latest != null &&
          latest.expense == expense &&
          store.pendingCorrection(owner, home, id) == null) {
        store.save(latest.withProgress(true, ExpenseProgress.CONFIRMED))
      }
    }
    store.loadById(owner, home, id)?.let { upload(it) }
    return true
  }
}
