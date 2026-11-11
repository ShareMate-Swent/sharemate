// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.profile

import kotlinx.coroutines.flow.Flow

/** Stores public profiles and reads the profiles of a household's members. */
interface UserProfileRepository {

  /**
   * Saves [profile] as the public profile of [UserProfile.uid]. An existing non-blank display name
   * is retained so sign-in does not overwrite a user's chosen name; other fields are refreshed.
   * Only the signed-in user can save their own profile.
   *
   * @throws IllegalArgumentException if [UserProfile.uid] is blank.
   * @throws Exception if the data source cannot save the profile.
   */
  suspend fun savePublicProfile(profile: UserProfile)

  /**
   * Emits the profiles of the members of [householdId], in the household's member order, and emits
   * again whenever a member joins or leaves. A member who has not saved a profile yet is emitted
   * with only their [UserProfile.uid]. Emits an empty list if the household does not exist.
   *
   * The flow fails if the data source cannot read the household or a profile, e.g. offline or when
   * the signed-in user is not a member.
   *
   * @throws IllegalArgumentException if [householdId] is blank.
   */
  fun observeMembers(householdId: String): Flow<List<UserProfile>>
}
