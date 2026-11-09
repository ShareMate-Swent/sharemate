// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [UserProfileRepository] for unit tests. Like the Firestore one, it lists members in the
 * household's order and returns only the uid for members without a saved profile.
 */
class InMemoryUserProfileRepository : UserProfileRepository {
  /** Saved profiles by user id. */
  val profiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())

  /** Member ids by household id; update it to simulate joins and leaves. */
  val memberIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

  /** Every profile passed to [savePublicProfile], in call order. */
  val saved = mutableListOf<UserProfile>()

  /** When set, [savePublicProfile] throws it instead of saving. */
  var saveFailure: Exception? = null

  override suspend fun savePublicProfile(profile: UserProfile) {
    require(profile.uid.isNotBlank()) { "A profile needs a user id" }
    saved.add(profile)
    saveFailure?.let { throw it }
    profiles.value +=
        profile.uid to profile.withExistingDisplayName(profiles.value[profile.uid]?.displayName)
  }

  override fun observeMembers(householdId: String): Flow<List<UserProfile>> {
    require(householdId.isNotBlank()) { "A household id is required" }
    return memberIds.map { households ->
      households[householdId].orEmpty().map { id -> profiles.value[id] ?: UserProfile(id) }
    }
  }
}
