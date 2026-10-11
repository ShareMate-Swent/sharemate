// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

/** Provides household creation, joining, leaving, and lookup operations. */
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
   *   invalid. An unknown invite code also throws [IllegalArgumentException]. If [userId] is
   *   already a member, the operation has no effect and the household is returned.
   * @throws Exception if the data source cannot join the household.
   */
  suspend fun joinHousehold(inviteCode: String, userId: String): Household

  /**
   * Removes [userId] from the household they belong to and unlinks it from their profile.
   *
   * Has no effect when the user belongs to no household. The household and its invite code are
   * kept, even when its last member leaves, so the user can join again with the same code.
   *
   * @throws IllegalArgumentException if [userId] is blank.
   * @throws Exception if the data source cannot leave the household.
   */
  suspend fun leaveHousehold(userId: String)

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
