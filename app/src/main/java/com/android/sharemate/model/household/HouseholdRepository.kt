// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

/** Provides household creation, joining, and lookup operations. */
interface HouseholdRepository {

  /**
   * Creates a household with [creatorId] as its first member.
   *
   * @throws IllegalArgumentException if [name] or [creatorId] is blank.
   * @throws Exception if the data source cannot create the household.
   */
  suspend fun createHousehold(name: String, creatorId: String): Household

  /**
   * Adds [userId] to the household identified by [inviteCode].
   *
   * @throws IllegalArgumentException if [inviteCode] or [userId] is blank, or the invite code is
   *   invalid.
   * @throws IllegalStateException if [userId] is already a member of the household.
   * @throws Exception if the data source cannot join the household.
   */
  suspend fun joinHousehold(inviteCode: String, userId: String): Household

  /**
   * Looks up a household by its identifier.
   *
   * @return The matching household, or null when no household exists with [householdId].
   * @throws Exception if the data source cannot perform the lookup.
   */
  suspend fun getHousehold(householdId: String): Household?

  /**
   * Looks up the household containing [userId].
   *
   * @return The user's household, or null when the user belongs to no household.
   * @throws Exception if the data source cannot perform the lookup.
   */
  suspend fun getHouseholdForUser(userId: String): Household?
}
