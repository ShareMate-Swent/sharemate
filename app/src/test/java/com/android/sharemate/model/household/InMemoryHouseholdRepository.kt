// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import java.util.Date
import java.util.UUID

/** In-memory repository for ViewModel tests that do not require Firestore. */
class InMemoryHouseholdRepository : HouseholdRepository {
  private val households = mutableMapOf<String, Household>()

  override suspend fun createHousehold(name: String, creatorId: String): Household {
    require(name.isNotBlank()) { "Household name must not be blank" }
    require(creatorId.isNotBlank()) { "Creator ID must not be blank" }

    val household =
        Household(
            id = UUID.randomUUID().toString(),
            name = name,
            inviteCode = UUID.randomUUID().toString().take(8).uppercase(),
            memberIds = listOf(creatorId),
            createdBy = creatorId,
            createdAt = Date())
    households[household.id] = household
    return household
  }

  override suspend fun joinHousehold(inviteCode: String, userId: String): Household {
    require(inviteCode.isNotBlank()) { "Invite code must not be blank" }
    require(userId.isNotBlank()) { "User ID must not be blank" }

    val normalizedCode = inviteCode.trim().uppercase()
    val household =
        households.values.firstOrNull { it.inviteCode == normalizedCode }
            ?: throw IllegalArgumentException("Invalid invite code")
    if (userId in household.memberIds) return household

    return household.copy(memberIds = household.memberIds + userId).also {
      households[household.id] = it
    }
  }

  override suspend fun getHousehold(householdId: String): Household? = households[householdId]

  override suspend fun getHouseholdForUser(userId: String): Household? =
      households.values.firstOrNull { userId in it.memberIds }
}
