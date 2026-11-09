// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow

/** Names intentionally shared with current fridge members; never exposes private user profiles. */
interface MemberNameRepository {
  /** Emits an empty map until server-confirmed membership and directory results are available. */
  fun observeNames(householdId: String, viewerId: String): Flow<Map<String, String>>

  /** Publishes only this user's name, using their private profile if the auth name is missing. */
  suspend fun publishOwnName(householdId: String, userId: String, authName: String?)
}

/**
 * No email fallback, raw identifiers, or unbounded/multiline profile values in inventory labels.
 */
internal fun ownerDisplayName(raw: String?): String? =
    raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf {
      it.isNotEmpty() && it.length <= 80 && '@' !in it
    }
