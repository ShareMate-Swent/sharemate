// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import kotlinx.coroutines.flow.emptyFlow

internal object UnusedReceiptCloud : ReceiptCloudRepository {
  override fun observe(ownerId: String, householdId: String) = emptyFlow<List<ReceiptCloudEntry>>()

  override fun observeDeleted(ownerId: String, householdId: String) = emptyFlow<Set<String>>()
}

internal object UnusedReceiptExpenses : ExpenseSubmissionRepository {
  override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
      error("Unexpected expense write")
}
