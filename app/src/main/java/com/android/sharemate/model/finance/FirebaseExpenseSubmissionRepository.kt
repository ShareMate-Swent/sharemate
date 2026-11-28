// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.finance.FinanceFields.HOUSEHOLD_ID
import com.android.sharemate.model.finance.FinanceFields.ID
import com.android.sharemate.model.finance.FinanceFields.PAID_BY
import com.android.sharemate.model.finance.FinanceFields.RECEIPT
import com.android.sharemate.model.finance.FinanceFields.SHARES
import com.android.sharemate.model.finance.FinanceFields.TOTAL_AMOUNT_CENTS
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as RowFields
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/** Inject a Functions instance configured for [REGION], with the current authenticated session. */
class FirebaseExpenseSubmissionRepository(private val functions: FirebaseFunctions) :
    ExpenseSubmissionRepository {
  override suspend fun deleteReceipt(householdId: String, id: String) {
    val result =
        functions
            .getHttpsCallable("deleteReceipt")
            .call(mapOf("householdId" to householdId, "id" to id))
            .await()
            .data
    check(result == mapOf("id" to id)) { "Unexpected deletion response" }
  }

  override suspend fun correctReceipt(
      correction: com.android.sharemate.model.staging.ReceiptCorrection
  ) {
    fun expense(value: SharedExpense) =
        mapOf(
            ID to value.id,
            HOUSEHOLD_ID to value.householdId,
            PAID_BY to value.paidBy,
            TOTAL_AMOUNT_CENTS to value.totalAmountCents.toString(),
            SHARES to value.shares.mapValues { it.value.toString() })
    fun rows(value: InventorySubmission) =
        value.items.map {
          mapOf(
                  RowFields.ID to it.id,
                  RowFields.NAME to it.name,
                  RowFields.QUANTITY to it.quantity,
                  RowFields.PRICE to it.priceCents?.toString())
              .let { fields ->
                // Keep the original payload for retries of corrections created by older clients.
                if (it.includedInExpense) fields
                else fields + (RowFields.INCLUDED_IN_EXPENSE to false)
              }
        }
    val payload =
        mapOf(
                "operationId" to correction.operationId,
                "ownerId" to correction.original.ownerId,
                "originalRows" to rows(correction.original),
                "originalTitle" to correction.originalTitle,
                "originalRevision" to correction.originalRevision,
                "rows" to rows(correction.replacement),
                "originalExpense" to correction.original.expense?.let(::expense),
                "expense" to correction.replacement.expense?.let(::expense),
                "title" to correction.title)
            .let { fields ->
              if (correction.replacement.expense != null) fields
              else
                  fields +
                      (RECEIPT to
                          mapOf(
                              ID to correction.original.id,
                              HOUSEHOLD_ID to correction.original.householdId))
            }
    val response = functions.getHttpsCallable("correctReceipt").call(payload).await().data
    check(response == mapOf("operationId" to correction.operationId)) {
      "Unexpected correction response"
    }
  }

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
          val reason = FinanceCallablePolicy.rejectionReason(error.code)
          if (reason != null) throw ExpenseRejectedException(reason, error)
          throw error
        }
    check(response == payload) { "Unexpected expense response" }
    return expense
  }

  companion object {
    /** Region hosting the financial callables. */
    const val REGION = FinanceCallablePolicy.REGION
  }
}
