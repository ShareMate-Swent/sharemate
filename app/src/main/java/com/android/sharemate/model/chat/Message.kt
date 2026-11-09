// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import java.util.Date

/**
 * A chat message stored in the `messages` sub-collection of a household document
 * (`households/{householdId}/messages/{messageId}`).
 *
 * @property id Message identifier (the Firestore document id).
 * @property senderId Firebase UID of the author.
 * @property senderName Display name of the author at send time.
 * @property text Message content.
 * @property timestamp When the message was sent.
 * @property type Whether this is a text message or a fridge activity (eaten / thrown).
 * @property itemName Name of the concerned item, for activity messages.
 * @property itemImageUrl Optional image of the concerned item, for activity messages.
 */
data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    val timestamp: Date? = null,
    val type: MessageType = MessageType.TEXT,
    val itemName: String = "",
    val itemImageUrl: String? = null
) {
  /** Whether this message was sent by [userId]. */
  fun isSentBy(userId: String): Boolean = senderId.isNotEmpty() && senderId == userId

  companion object {
    const val MAX_TEXT_LENGTH = 2000

    /** Normalizes user input before it is sent. */
    fun normalizeText(raw: String): String = raw.trim()

    /** Whether [raw] can be sent as a message once normalized. */
    fun isValidText(raw: String): Boolean {
      val text = normalizeText(raw)
      return text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH
    }
  }
}

/** Kind of chat message; non-text kinds are fridge activities shown as badges. */
enum class MessageType {
  TEXT,
  EATEN,
  THROWN;

  companion object {
    /** Parses a stored value, falling back to [TEXT] for missing or unknown ones. */
    fun fromStored(value: String?): MessageType = entries.firstOrNull { it.name == value } ?: TEXT
  }
}
