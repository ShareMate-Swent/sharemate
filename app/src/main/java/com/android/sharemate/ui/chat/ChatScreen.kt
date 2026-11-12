// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.model.chat.Message
import com.android.sharemate.ui.theme.Dimens
import java.text.DateFormat

object ChatTestTags {
  const val SCREEN = "chat_screen"
  const val LIST = "chat_list"
  const val EMPTY = "chat_empty"
  const val LOADING = "chat_loading"
  const val ERROR = "chat_error"
  const val INPUT = "chat_input"
  const val SEND = "chat_send"
  const val OWN_MESSAGE = "chat_message_own"
  const val OTHER_MESSAGE = "chat_message_other"
}

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
  LaunchedEffect(messages.lastOrNull()?.id, messages.size) {
    if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
  }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(ChatTestTags.LIST),
      state = listState,
      contentPadding = PaddingValues(horizontal = Dimens.spacingS),
      verticalArrangement = Arrangement.spacedBy(ChatLayout.messageSpacing)) {
        items(messages, key = { it.id }) { message ->
          MessageBubble(message, own = message.isSentBy(currentUserId))
        }
      }
}

@Composable
private fun MessageBubble(message: Message, own: Boolean) {
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
                      message.senderName,
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.primary)
                }
                Text(message.text)
                message.timestamp?.let {
                  Text(
                      DateFormat.getTimeInstance(DateFormat.SHORT).format(it),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
      }
}
