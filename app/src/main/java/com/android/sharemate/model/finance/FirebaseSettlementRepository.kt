// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/** Records reimbursements through the authenticated callable, using the caller's stable ID. */
class FirebaseSettlementRepository(private val functions: FirebaseFunctions) :
    SettlementRepository {
  override suspend fun recordSettlement(settlement: Settlement): Settlement {
    val payload =
        mapOf(
            "id" to settlement.id,
            "householdId" to settlement.householdId,
            "payerId" to settlement.payerId,
            "recipientId" to settlement.recipientId,
            "amountCents" to settlement.amountCents.toString(),
        )
    val response =
        try {
          functions.getHttpsCallable("recordSettlement").call(payload).await().data
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
