// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.chat

import com.android.sharemate.model.incident.IncidentPhotoReference
import kotlinx.coroutines.flow.Flow

/** Access to the group chat of a shared household. */
interface ChatRepository {
  /** Streams the household's messages ordered by timestamp, oldest first. */
  fun observeMessages(householdId: String): Flow<List<Message>>

  /**
   * Sends [text] as [senderId]; returns the new message id after acknowledgement, or null if
   * invalid or failed. The cached profile name is preferred; [senderName] is used on a cache miss.
   */
  suspend fun sendMessage(
      householdId: String,
      senderId: String,
      senderName: String,
      text: String
  ): String?

  /**
   * Posts a fridge activity ([type] EATEN or THROWN) on [itemName] done by [senderId]; returns the
   * new message id after acknowledgement, or null if invalid or rejected. Throws
   * [TransientActivityException] when the failure is transient and the caller should retry. The
   * cached profile name is preferred; [senderName] is used on a cache miss.
   */
  suspend fun sendActivity(
      householdId: String,
      senderId: String,
      senderName: String,
      type: MessageType,
      itemName: String,
      itemImageUrl: String? = null,
      /** Stable action key used to make chat publication idempotent, for example itemId:TYPE. */
      activityId: String? = null,
  ): String?

  /**
   * Posts an incident reported by [senderId]: a [description] and the [photo] already uploaded with
   * `IncidentPhotoRepository.upload`. The photo must have been uploaded by [senderId] for
   * [householdId] and must exist in Storage; a missing photo, or a check that cannot be made,
   * refuses the post. Returns the new message id after acknowledgement, or null if invalid or
   * failed. The cached profile name is preferred; [senderName] is used on a cache miss.
   */
  suspend fun sendIncident(
      householdId: String,
      senderId: String,
      senderName: String,
      description: String,
      photo: IncidentPhotoReference
  ): String?
}

/**
 * Thrown by [ChatRepository.sendActivity] when publication failed for a transient reason (for
 * example the backend is unreachable) and may succeed if retried, unlike a null result which means
 * the activity was rejected.
 */
class TransientActivityException(cause: Throwable) : Exception(cause.message, cause)
