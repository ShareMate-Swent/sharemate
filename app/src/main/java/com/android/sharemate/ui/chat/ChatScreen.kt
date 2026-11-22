// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.ui.theme.Dimens
import java.text.DateFormat
import java.util.Locale

/** Semantic test tags for the chat screen and its messages. */
object ChatTestTags {
  /** Tag for the chat screen container. */
  const val SCREEN = "chat_screen"

  /** Tag for the scrollable message list. */
  const val LIST = "chat_list"

  /** Tag for the empty-chat message. */
  const val EMPTY = "chat_empty"

  /** Tag for the initial loading indicator. */
  const val LOADING = "chat_loading"

  /** Tag for chat errors. */
  const val ERROR = "chat_error"

  /** Tag for the message composer. */
  const val INPUT = "chat_input"

  /** Tag for the send button. */
  const val SEND = "chat_send"

  /** Tag for messages sent by the current user. */
  const val OWN_MESSAGE = "chat_message_own"

  /** Tag for messages sent by another household member. */
  const val OTHER_MESSAGE = "chat_message_other"

  /** Tag for fridge activity badges. */
  const val ACTIVITY = "chat_activity"
}

/**
 * Displays the household chat and forwards user actions to [viewModel].
 *
 * @param viewModel Provides the chat state and actions.
 * @param modifier Modifier applied to the screen.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ChatContent(
      state = state,
      currentUserId = viewModel.userId,
      onDraftChange = viewModel::onDraftChange,
      onSend = viewModel::send,
      modifier = modifier)
}

/**
 * Renders the chat screen from [state].
 *
 * @param state Current chat content and composer state.
 * @param currentUserId UID used to identify messages sent by the current user.
 * @param onDraftChange Called when the composer text changes.
 * @param onSend Called when the user requests to send a message.
 * @param modifier Modifier applied to the screen.
 */
@Composable
fun ChatContent(
    state: ChatUiState,
    currentUserId: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().testTag(ChatTestTags.SCREEN)) {
    Text(
        text = stringResource(R.string.chat_title),
        modifier = Modifier.padding(Dimens.spacingM),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        state.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(ChatTestTags.LOADING))
        state.messages.isEmpty() && state.error == ChatError.LOAD_FAILED -> Unit
        state.messages.isEmpty() ->
            Text(
                stringResource(R.string.chat_empty),
                Modifier.align(Alignment.Center).testTag(ChatTestTags.EMPTY))
        else -> MessageList(state.messages, currentUserId)
      }
    }
    state.error?.let {
      Text(
          text =
              when (it) {
                ChatError.LOAD_FAILED -> stringResource(R.string.chat_load_error)
                ChatError.SEND_FAILED -> stringResource(R.string.chat_send_error)
              },
          modifier = Modifier.padding(horizontal = Dimens.spacingM).testTag(ChatTestTags.ERROR),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall)
    }
    Row(
        Modifier.fillMaxWidth().padding(Dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
          OutlinedTextField(
              value = state.draft,
              onValueChange = onDraftChange,
              modifier = Modifier.weight(1f).testTag(ChatTestTags.INPUT),
              placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
              maxLines = ChatLayout.composerMaxLines)
          IconButton(
              onClick = onSend,
              enabled = state.canSend,
              modifier = Modifier.testTag(ChatTestTags.SEND)) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send))
              }
        }
  }
}

@Composable
private fun MessageList(messages: List<Message>, currentUserId: String) {
  val listState = rememberLazyListState()
  val defaultName = stringResource(R.string.chat_default_sender_name)
  val resources = LocalContext.current.resources
  val configuration = LocalConfiguration.current
  val disambiguators =
      remember(messages, defaultName, configuration) {
        senderDisambiguators(messages, defaultName) { name, number ->
          resources.getString(R.string.chat_sender_identity, name, number)
        }
      }
  LaunchedEffect(messages.lastOrNull()?.id, messages.size) {
    if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
  }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(ChatTestTags.LIST),
      state = listState,
      contentPadding = PaddingValues(horizontal = Dimens.spacingS),
      verticalArrangement = Arrangement.spacedBy(ChatLayout.messageSpacing)) {
        items(messages, key = { it.id }) { message ->
          MessageBubble(
              message,
              own = message.isSentBy(currentUserId),
              senderName = displaySenderName(message.senderName, defaultName),
              disambiguator = disambiguators[message.senderId])
        }
      }
}

@Composable
private fun MessageBubble(
    message: Message,
    own: Boolean,
    senderName: String,
    disambiguator: Int?
): Unit =
    when (message.type) {
      // Incidents show their description until the photo card of US-7 is built.
      MessageType.TEXT,
      MessageType.INCIDENT -> TextMessageBubble(message, own, senderName, disambiguator)
      MessageType.EATEN,
      MessageType.THROWN -> ActivityBadge(message, senderName, disambiguator)
    }

@Composable
private fun TextMessageBubble(
    message: Message,
    own: Boolean,
    senderName: String,
    disambiguator: Int?
) {
  Box(
      Modifier.fillMaxWidth(),
      contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier =
                Modifier.widthIn(max = ChatLayout.bubbleMaxWidth)
                    .testTag(if (own) ChatTestTags.OWN_MESSAGE else ChatTestTags.OTHER_MESSAGE),
            shape = RoundedCornerShape(ChatLayout.bubbleCornerRadius),
            color =
                if (own) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant) {
              Column(Modifier.padding(horizontal = Dimens.spacingS, vertical = Dimens.spacingXs)) {
                if (!own) {
                  Text(
                      chatSenderLabel(senderName, disambiguator),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.primary)
                }
                Text(message.text)
                message.timestamp?.let {
                  Text(
                      DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
      }
}

@Composable
private fun ActivityBadge(message: Message, senderName: String, disambiguator: Int?) {
  val isEaten = message.type == MessageType.EATEN
  val actionText =
      if (isEaten) stringResource(R.string.chat_activity_eaten, message.itemName)
      else stringResource(R.string.chat_activity_thrown, message.itemName)
  val containerColor =
      if (isEaten) MaterialTheme.colorScheme.tertiaryContainer
      else MaterialTheme.colorScheme.errorContainer
  val contentColor =
      if (isEaten) MaterialTheme.colorScheme.onTertiaryContainer
      else MaterialTheme.colorScheme.onErrorContainer
  val actionIcon = if (isEaten) Icons.Filled.Restaurant else Icons.Filled.DeleteOutline

  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(
        modifier = Modifier.widthIn(max = ChatLayout.bubbleMaxWidth).testTag(ChatTestTags.ACTIVITY),
        shape = RoundedCornerShape(Dimens.spacingM),
        color = containerColor,
        contentColor = contentColor) {
          Row(
              modifier = Modifier.fillMaxWidth().padding(Dimens.spacingS),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.activityBadgeIconSize))
                Column(Modifier.weight(1f)) {
                  Text(
                      chatSenderLabel(senderName, disambiguator),
                      style = MaterialTheme.typography.labelMedium)
                  Text(actionText, style = MaterialTheme.typography.bodyMedium)
                  message.timestamp?.let {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(it),
                        style = MaterialTheme.typography.labelSmall)
                  }
                }
                message.itemImageUrl?.let { imageUrl ->
                  AsyncImage(
                      model = imageUrl,
                      contentDescription =
                          stringResource(
                              R.string.chat_activity_image_description, message.itemName),
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.size(Dimens.activityBadgeImageSize))
                }
              }
        }
  }
}

@Composable
private fun chatSenderLabel(senderName: String, disambiguator: Int?): String =
    if (disambiguator == null) senderName
    else stringResource(R.string.chat_sender_identity, senderName, disambiguator)

private fun displaySenderName(name: String, defaultName: String): String =
    name.trim().takeIf { it.isNotEmpty() && '@' !in it } ?: defaultName

internal fun senderDisambiguators(
    messages: List<Message>,
    defaultName: String,
    formatLabel: (String, Int) -> String
): Map<String, Int> {
  val senderNumbers =
      messages.map { it.senderId }.distinct().withIndex().associate { it.value to it.index + 1 }
  val ambiguousSenders = mutableSetOf<String>()
  do {
    val labels =
        messages.groupBy { message ->
          val name = displaySenderName(message.senderName, defaultName)
          val label =
              if (message.senderId in ambiguousSenders)
                  formatLabel(name, senderNumbers.getValue(message.senderId))
              else name
          label.lowercase(Locale.ROOT)
        }
    val previousSize = ambiguousSenders.size
    labels.values
        .filter { group -> group.map { it.senderId }.distinct().size > 1 }
        .forEach { group -> ambiguousSenders.addAll(group.map { it.senderId }) }
  } while (ambiguousSenders.size > previousSize)
  return senderNumbers.filterKeys { it in ambiguousSenders }
}
