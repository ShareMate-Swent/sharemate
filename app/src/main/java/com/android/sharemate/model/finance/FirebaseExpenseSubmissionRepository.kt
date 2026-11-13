// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.finance.FinanceFields.HOUSEHOLD_ID
import com.android.sharemate.model.finance.FinanceFields.ID
import com.android.sharemate.model.finance.FinanceFields.PAID_BY
import com.android.sharemate.model.finance.FinanceFields.SHARES
import com.android.sharemate.model.finance.FinanceFields.TOTAL_AMOUNT_CENTS
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/** Inject a Functions instance configured for [REGION], with the current authenticated session. */
class FirebaseExpenseSubmissionRepository(private val functions: FirebaseFunctions) :
    ExpenseSubmissionRepository {
  override suspend fun recordExpense(expense: SharedExpense): SharedExpense {
    val payload =
        mapOf(
            ID to expense.id,
            HOUSEHOLD_ID to expense.householdId,
            PAID_BY to expense.paidBy,
            TOTAL_AMOUNT_CENTS to expense.totalAmountCents.toString(),
            SHARES to expense.shares.mapValues { it.value.toString() },
        )
    val response =
        try {
          functions.getHttpsCallable(FinanceFunctions.RECORD_EXPENSE).call(payload).await().data
        } catch (error: FirebaseFunctionsException) {
          if (error.code in DEFINITIVE_REJECTIONS) throw ExpenseRejectedException(error)
          throw error
        }
    check(response == payload) { "Unexpected expense response" }
    return expense
  }

  companion object {
    const val REGION = "europe-west6"
    private val DEFINITIVE_REJECTIONS =
        setOf(
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS,
        )
  }
}
