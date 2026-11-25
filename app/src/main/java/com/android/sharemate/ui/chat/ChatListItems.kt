// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One row of the chat list: a day separator or a message. */
internal sealed interface ChatListItem {
  /** Stable list key; the prefixes keep separators and messages from ever sharing a key. */
  val key: String
}

/**
 * Separator shown before the first message of [day].
 *
 * The same day can appear twice: Firestore sorts pending server timestamps after confirmed
 * messages, even when their local estimate is earlier. The key therefore comes from the first
 * message of the group ([firstMessageId]), which is unique, rather than from [day].
 */
internal data class DaySeparator(val day: LocalDate, val firstMessageId: String) : ChatListItem {
  override val key: String
    get() = "day:$firstMessageId"
}

/**
 * A message row.
 *
 * @property own Whether the current user sent it; own rows are aligned to the right.
 * @property showName Whether the sender's name is shown above it (first of a run).
 * @property showAvatar Whether the sender's avatar is shown next to it (last of a run).
 */
internal data class MessageItem(
    val message: Message,
    val own: Boolean,
    val showName: Boolean,
    val showAvatar: Boolean,
) : ChatListItem {
  override val key: String
    get() = "message:${message.id}"
}

/** A member shown in the chat header. */
internal data class ChatParticipant(val senderId: String, val senderName: String)

/** Whether [this] is shown as a bubble, as opposed to a fridge activity chip. */
internal val Message.isBubble: Boolean
  get() = type == MessageType.TEXT || type == MessageType.INCIDENT

/**
 * Builds the chat rows from [messages] (oldest first): a [DaySeparator] before each new day, and
 * runs of consecutive bubbles from the same other member, which show the name on their first
 * message and the avatar on their last. A fridge activity or a new day ends a run. A message
 * without timestamp is still being sent, so it belongs to [today].
 */
internal fun buildChatItems(
    messages: List<Message>,
    currentUserId: String,
    today: LocalDate,
    zone: ZoneId,
): List<ChatListItem> {
  val days =
      messages.map { message ->
        message.timestamp?.toInstant()?.atZone(zone)?.toLocalDate() ?: today
      }
  fun continuesRun(previous: Int, next: Int): Boolean {
    val a = messages[previous]
    val b = messages[next]
    return days[previous] == days[next] &&
        a.isBubble &&
        b.isBubble &&
        a.senderId == b.senderId &&
        !a.isSentBy(currentUserId)
  }
  val items = mutableListOf<ChatListItem>()
  messages.forEachIndexed { index, message ->
    if (index == 0 || days[index] != days[index - 1]) {
      items += DaySeparator(days[index], message.id)
    }
    val own = message.isSentBy(currentUserId)
    // Activities carry the name in their text and never show an avatar.
    val grouped = !own && message.isBubble
    items +=
        MessageItem(
            message = message,
            own = own,
            showName = grouped && (index == 0 || !continuesRun(index - 1, index)),
            showAvatar =
                grouped && (index == messages.lastIndex || !continuesRun(index, index + 1)),
        )
  }
  return items
}

/**
 * The other members who wrote in [messages], in order of first appearance, at most
 * [ChatLayout.maxHeaderAvatars]. Each keeps the name of their latest message.
 */
internal fun headerParticipants(
    messages: List<Message>,
    currentUserId: String
): List<ChatParticipant> {
  val latestNames = mutableMapOf<String, String>()
  messages.forEach { message ->
    if (message.senderId.isNotEmpty() && !message.isSentBy(currentUserId)) {
      latestNames[message.senderId] = message.senderName
    }
  }
  // A LinkedHashMap keeps the order in which each sender first appeared.
  return latestNames.entries.take(ChatLayout.maxHeaderAvatars).map {
    ChatParticipant(it.key, it.value)
  }
}

/**
 * Up to two upper-case letters for an avatar: the first letter of the first two words, or the first
 * two letters of a single word ("Lucía" gives "LU", "Alex Martin" gives "AM"). Blank names give an
 * empty string. Letters are counted in code points, so an emoji is never split.
 */
internal fun initials(name: String): String {
  val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
  val letters =
      when {
        words.isEmpty() -> emptyList()
        words.size == 1 -> words.first().codePointPrefix(ChatLayout.maxInitials)
        else -> words.take(ChatLayout.maxInitials).flatMap { it.codePointPrefix(1) }
      }
  return letters.joinToString("").uppercase(Locale.getDefault())
}

private fun String.codePointPrefix(count: Int): List<String> =
    codePoints().limit(count.toLong()).toArray().map { String(Character.toChars(it)) }
