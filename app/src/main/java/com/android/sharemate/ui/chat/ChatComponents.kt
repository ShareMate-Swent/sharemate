// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.ui.theme.Dimens
import com.android.sharemate.ui.theme.ShareMateTheme
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * A member's round avatar: their [photoUrl] drawn over their initials, which stay visible while the
 * photo loads, when it fails, or when there is none.
 *
 * @param name Display name of the member, used for the initials and the photo description.
 */
@Composable
internal fun ChatAvatar(name: String, photoUrl: String?, modifier: Modifier = Modifier) {
  Box(
      modifier
          .size(ChatLayout.avatarSize)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center) {
        Text(
            initials(name),
            modifier = Modifier.testTag(ChatTestTags.AVATAR_INITIALS),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (photoUrl != null) {
          AsyncImage(
              model = photoUrl,
              contentDescription = stringResource(R.string.chat_avatar_description, name),
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize().testTag(ChatTestTags.AVATAR_PHOTO))
        }
      }
}

/**
 * The screen title, the household's [householdName] and [memberCount], and the avatars of the other
 * members who wrote in the chat.
 *
 * @param avatarName Display name of a participant.
 */
@Composable
internal fun ChatHeader(
    householdName: String,
    memberCount: Int,
    participants: List<ChatParticipant>,
    memberPhotos: Map<String, String>,
    avatarName: (ChatParticipant) -> String,
) {
  Row(
      Modifier.fillMaxWidth().padding(Dimens.spacingM),
      verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
              stringResource(R.string.chat_title),
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onBackground)
          headerSubtitle(householdName, memberCount)?.let {
            Text(
                it,
                modifier = Modifier.testTag(ChatTestTags.SUBTITLE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        if (participants.isNotEmpty()) {
          Row(
              Modifier.testTag(ChatTestTags.HEADER_AVATARS),
              horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                participants.forEach { ChatAvatar(avatarName(it), memberPhotos[it.senderId]) }
              }
        }
      }
}

@Composable
private fun headerSubtitle(householdName: String, memberCount: Int): String? {
  val count =
      if (memberCount > 0)
          pluralStringResource(R.plurals.chat_member_count, memberCount, memberCount)
      else null
  val name = householdName.trim().takeIf { it.isNotEmpty() }
  return when {
    name != null && count != null -> stringResource(R.string.chat_header_subtitle, name, count)
    else -> name ?: count
  }
}

/** The empty chat: an icon in an accent circle and an invitation to write. */
@Composable
internal fun EmptyChat(modifier: Modifier = Modifier) {
  Column(
      modifier.testTag(ChatTestTags.EMPTY),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
        Box(
            Modifier.size(ChatLayout.emptyIconContainerSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
              Icon(
                  Icons.Outlined.ChatBubbleOutline,
                  contentDescription = null,
                  modifier = Modifier.size(ChatLayout.emptyIconSize),
                  tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        Text(
            stringResource(R.string.chat_empty),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
      }
}

/** Label before the first message of [day]: "Today", "Yesterday" or a localized date. */
@Composable
internal fun DaySeparatorLabel(day: LocalDate, today: LocalDate) {
  val label =
      when (day) {
        today -> stringResource(R.string.chat_today)
        today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        else ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(day)
      }
  Text(
      label,
      modifier =
          Modifier.padding(top = Dimens.spacingXs, start = Dimens.spacingXxs)
              .testTag(ChatTestTags.DAY_SEPARATOR),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A text or incident message. Own messages are accent bubbles on the right; the others are white
 * bubbles on the left, with the sender's name above the first of a run and their avatar next to the
 * last.
 *
 * @param senderLabel Sender name shown above the bubble, disambiguated if needed.
 * @param avatarName Sender name used for the avatar's initials.
 */
@Composable
internal fun MessageRow(
    item: MessageItem,
    senderLabel: String,
    avatarName: String,
    photoUrl: String?,
) {
  if (item.own) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
      MessageBubble(item.message, own = true)
    }
    return
  }
  Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        if (item.showAvatar) ChatAvatar(avatarName, photoUrl)
        else Spacer(Modifier.size(ChatLayout.avatarSize))
        Column {
          if (item.showName) {
            Text(
                senderLabel,
                modifier = Modifier.padding(start = Dimens.spacingXxs, bottom = Dimens.spacingXxs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          MessageBubble(item.message, own = false)
        }
      }
}

@Composable
private fun MessageBubble(message: Message, own: Boolean) {
  val contentColor =
      if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  Surface(
      modifier =
          Modifier.widthIn(max = ChatLayout.bubbleMaxWidth)
              .testTag(if (own) ChatTestTags.OWN_MESSAGE else ChatTestTags.OTHER_MESSAGE),
      shape = RoundedCornerShape(ChatLayout.bubbleCornerRadius),
      color = if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
      contentColor = contentColor) {
        Column(Modifier.padding(horizontal = Dimens.spacingS, vertical = Dimens.spacingXs)) {
          Text(message.text, style = MaterialTheme.typography.bodyMedium)
          message.timestamp?.let {
            Text(
                formatTime(it),
                modifier = Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = if (own) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
}

/**
 * A fridge activity: a white chip with a green check (eaten) or a red bin (thrown), "**Name** ate
 * Item" and the time. Own activities sit on the right, the others' on the left.
 *
 * @param senderLabel Sender name shown in bold, disambiguated if needed.
 */
@Composable
internal fun ActivityChip(message: Message, own: Boolean, senderLabel: String) {
  val isEaten = message.type == MessageType.EATEN
  val status =
      if (isEaten) ShareMateTheme.statusColors.fresh else ShareMateTheme.statusColors.expired
  val text =
      if (isEaten) stringResource(R.string.chat_activity_eaten, senderLabel, message.itemName)
      else stringResource(R.string.chat_activity_thrown, senderLabel, message.itemName)
  val nameStart = text.indexOf(senderLabel)
  val annotated = buildAnnotatedString {
    append(text)
    if (nameStart >= 0) {
      addStyle(SpanStyle(fontWeight = FontWeight.Bold), nameStart, nameStart + senderLabel.length)
    }
  }
  Box(
      Modifier.fillMaxWidth(),
      contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier =
                Modifier.widthIn(max = ChatLayout.bubbleMaxWidth).testTag(ChatTestTags.ACTIVITY),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface) {
              Row(
                  Modifier.padding(
                      start = Dimens.spacingXxs,
                      end = Dimens.spacingS,
                      top = Dimens.spacingXxs,
                      bottom = Dimens.spacingXxs),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                    Box(
                        Modifier.size(ChatLayout.activityIconContainerSize)
                            .clip(CircleShape)
                            .background(status.container),
                        contentAlignment = Alignment.Center) {
                          Icon(
                              if (isEaten) Icons.Filled.Check else Icons.Filled.Delete,
                              contentDescription = null,
                              modifier = Modifier.size(ChatLayout.activityIconSize),
                              tint = status.content)
                        }
                    Text(
                        annotated,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall)
                    message.timestamp?.let {
                      Text(
                          formatTime(it),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
            }
      }
}

/** The message field and the accent send button, enabled only when [canSend]. */
@Composable
internal fun ChatComposer(
    draft: String,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
  Surface(
      Modifier.fillMaxWidth().padding(Dimens.spacingXs),
      shape = RoundedCornerShape(Dimens.cornerRadiusL),
      color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.padding(end = Dimens.spacingXs),
            verticalAlignment = Alignment.CenterVertically) {
              TextField(
                  value = draft,
                  onValueChange = onDraftChange,
                  modifier = Modifier.weight(1f).testTag(ChatTestTags.INPUT),
                  placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                  maxLines = ChatLayout.composerMaxLines,
                  colors =
                      TextFieldDefaults.colors(
                          focusedContainerColor = Color.Transparent,
                          unfocusedContainerColor = Color.Transparent,
                          disabledContainerColor = Color.Transparent,
                          focusedIndicatorColor = Color.Transparent,
                          unfocusedIndicatorColor = Color.Transparent,
                          disabledIndicatorColor = Color.Transparent))
              FilledIconButton(
                  onClick = onSend,
                  enabled = canSend,
                  modifier = Modifier.size(ChatLayout.sendButtonSize).testTag(ChatTestTags.SEND),
                  shape = RoundedCornerShape(Dimens.cornerRadiusS)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.chat_send))
                  }
            }
      }
}

private fun formatTime(date: Date): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
