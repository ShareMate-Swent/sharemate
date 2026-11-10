// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.finance.FinanceFields.AMOUNT_CENTS
import com.android.sharemate.model.finance.FinanceFields.HOUSEHOLD_ID
import com.android.sharemate.model.finance.FinanceFields.ID
import com.android.sharemate.model.finance.FinanceFields.PAYER_ID
import com.android.sharemate.model.finance.FinanceFields.RECIPIENT_ID
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/** Records reimbursements through the authenticated callable, using the caller's stable ID. */
class FirebaseSettlementRepository(private val functions: FirebaseFunctions) :
    SettlementRepository {
  override suspend fun recordSettlement(settlement: Settlement): Settlement {
    val payload =
        mapOf(
            ID to settlement.id,
            HOUSEHOLD_ID to settlement.householdId,
            PAYER_ID to settlement.payerId,
            RECIPIENT_ID to settlement.recipientId,
            AMOUNT_CENTS to settlement.amountCents.toString(),
        )
    val response =
        try {
          functions.getHttpsCallable(FinanceFunctions.RECORD_SETTLEMENT).call(payload).await().data
        } catch (error: FirebaseFunctionsException) {
          if (error.code in DEFINITIVE_REJECTIONS) throw SettlementRejectedException()
          throw error
        }
    check(response == payload) { "Unexpected settlement response" }
    return settlement
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
