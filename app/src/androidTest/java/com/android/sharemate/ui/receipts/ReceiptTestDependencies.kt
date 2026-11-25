// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense

internal object UnusedReceiptExpenses : ExpenseSubmissionRepository {
  override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
      error("Unexpected expense write")
}
