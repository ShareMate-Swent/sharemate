// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import kotlinx.coroutines.flow.Flow

/** Access to the group chat of a shared household. */
interface ChatRepository {
  /** Streams the household's messages ordered by timestamp, oldest first. */
  fun observeMessages(householdId: String): Flow<List<Message>>

  /** Sends [text] as [senderId]; returns the new message id, or null if invalid or failed. */
  suspend fun sendMessage(
      householdId: String,
      senderId: String,
      senderName: String,
      text: String
  ): String?

  /**
   * Posts a fridge activity ([type] EATEN or THROWN) on [itemName] done by [senderId]; returns the
   * new message id, or null if invalid or failed.
   */
  suspend fun sendActivity(
      householdId: String,
      senderId: String,
      senderName: String,
      type: MessageType,
      itemName: String,
      itemImageUrl: String? = null
  ): String?
}
