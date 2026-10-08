// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.util.Date
import kotlinx.coroutines.Deferred

/** Persisted as the exact enum name. Consumed/discarded items remain available in read flows. */
enum class ItemStatus {
  ACTIVE,
  EATEN,
  DISCARDED,
}

/**
 * The complete editable form. Null category/expiration clears that field. Callers must supply all
 * four values; identity, sharing, lifecycle and image metadata are deliberately excluded.
 */
data class ItemEdit(
    val name: String,
    val quantity: Int,
    val category: String?,
    val expirationDate: Date?,
)

/**
 * Submission to Firestore is separate from server acknowledgement. [Queued] means the SDK accepted
 * the write, not that authorization succeeded or that local listeners have already delivered it.
 * Observe the normal item flows for local application; do not await [Queued.confirmation] to close
 * an offline saving state. Confirmation may stay pending until connectivity returns, then contains
 * success or the server error (including missing documents and permission denial).
 *
 * Cancelling a caller's await does not cancel the SDK write. The handle is process-local;
 * Firestore's disk queue survives restarts, but this handle does not. No separate local item store
 * is maintained.
 */
sealed interface ItemWrite {
  data class Queued(val confirmation: Deferred<Result<Unit>>) : ItemWrite

  data class Rejected(val cause: Exception) : ItemWrite
}
