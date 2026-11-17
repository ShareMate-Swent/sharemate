// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Names intentionally shared with current fridge members; never exposes private user profiles. */
interface MemberNameRepository {
  /** Emits an empty map until server-confirmed membership and directory results are available. */
  fun observeNames(householdId: String, viewerId: String): Flow<Map<String, String>>

  /** Queues without awaiting acknowledgement; use [queueOwnName] to track server failures. */
  suspend fun publishOwnName(householdId: String, userId: String, authName: String?)

  /**
   * Returns a process-local confirmation handle after queuing; immediate failures throw. Awaiting
   * confirmation is optional and can stay pending offline. Cancelling an await does not cancel the
   * SDK mutation. Legacy implementations retain their original publication semantics.
   */
  suspend fun queueOwnName(
      householdId: String,
      userId: String,
      authName: String?
  ): Deferred<Result<Unit>> {
    publishOwnName(householdId, userId, authName)
    return CompletableDeferred(Result.success(Unit))
  }
}

/** No email/identifier fallback or unbounded/multiline profile values in labels. */
internal fun ownerDisplayName(raw: String?): String? =
    raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf {
      it.isNotEmpty() && it.length <= MAX_MEMBER_NAME_LENGTH && '@' !in it
    }

private const val MAX_MEMBER_NAME_LENGTH = 80
