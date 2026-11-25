// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.item.*
import kotlinx.coroutines.flow.first

/** Resumes journaled work using stable inventory identities without changing receipt ownership. */
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
      store.acknowledgeDeletion(owner, home, id)
      return true
    }
    val receipt = store.loadById(owner, home, id) ?: return true
    store.pendingCorrection(owner, home, id)?.let { correction ->
      expenses.correctReceipt(correction)
      store.acknowledgeCorrection(correction)
      if (actor == owner) store.loadById(owner, home, id)?.let { upload(it) }
      return true
    }
    if (actor != owner) return true
    if (receipt.draft) {
      upload(receipt)
      return true
    }
    val status = items.observeSubmission(receipt).first { it != InventorySubmissionStatus.PENDING }
    if (status == InventorySubmissionStatus.MISSING) {
      if (receipt.inventoryConfirmed || receipt.expenseProgress == ExpenseProgress.CONFIRMED)
          return true
      val write = store.recoverInventory(owner, home, id, items::addItems) ?: return true
      when (write) {
        is ItemWrite.Rejected -> throw write.cause
        is ItemWrite.Queued -> write.confirmation.await().getOrThrow()
      }
    }
    val expense = receipt.expense ?: return true
    if (receipt.expenseProgress != ExpenseProgress.CONFIRMED) {
      expenses.recordExpense(expense)
      store.acknowledgeExpense(owner, home, id, expense)
    }
    store.loadById(owner, home, id)?.let { upload(it) }
    return true
  }
}
