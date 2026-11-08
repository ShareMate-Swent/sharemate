// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.firebase.functions.FirebaseFunctions
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
    val response = functions.getHttpsCallable("recordSettlement").call(payload).await().data
    check(response == payload) { "Unexpected settlement response" }
    return settlement
  }

  companion object {
    const val REGION = "europe-west6"
  }
}
