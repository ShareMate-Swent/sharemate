// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

/**
 * Provides household creation, joining, leaving, and lookup operations.
 *
 * Membership is stored twice: in [Household.memberIds] and as `householdId` on the user's profile.
 * [Household.memberIds] is the source of truth: lookups and security rules read it. The profile
 * field is a copy that implementations must write in the same atomic operation, never alone.
 */
interface HouseholdRepository {

  /**
   * Creates a household with [creatorId] as its first member.
   *
   * [type] selects a private or shared fridge; existing callers create a shared fridge by default.
   * Private fridges have an empty [Household.inviteCode] and no invite-code lookup record.
   *
   * @throws IllegalArgumentException if [name] or [creatorId] is blank.
   * @throws Exception if the data source cannot create the household.
   */
  suspend fun createHousehold(
      name: String,
      creatorId: String,
      type: FridgeType = FridgeType.SHARED
  ): Household

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
   * The household and any shared invite code are kept, even when its last member leaves, so shared
   * households can be joined again with the same code. When no household lists the user, only a
   * stale household reference left on their profile is cleared; without one, nothing is written.
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
