// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository

/** Keeps receipt cache lifecycle aligned with successful household operations. */
class ReceiptHouseholdRepository(
    private val delegate: HouseholdRepository,
    private val evict: suspend (String, String) -> Unit,
    private val activate: suspend (String) -> Unit,
    private val beforeLeave: suspend (String, String) -> Unit = { _, _ -> },
) : HouseholdRepository by delegate {
  override suspend fun leaveHousehold(userId: String) {
    val household = delegate.getHouseholdForUser(userId)
    household?.takeIf { it.type == FridgeType.SHARED }?.let { beforeLeave(userId, it.id) }
    delegate.leaveHousehold(userId)
    household?.let { evict(userId, it.id) }
  }

  override suspend fun joinHousehold(inviteCode: String, userId: String): Household =
      delegate.joinHousehold(inviteCode, userId).also { activate(it.id) }

  override suspend fun createHousehold(
      name: String,
      creatorId: String,
      type: FridgeType,
      operationId: String
  ): Household =
      delegate.createHousehold(name, creatorId, type, operationId).also { activate(it.id) }

  override suspend fun getHouseholdForUser(userId: String): Household? =
      delegate.getHouseholdForUser(userId)?.also { activate(it.id) }
}
