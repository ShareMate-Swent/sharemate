// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the chat screen shows.
 *
 * @property messages The household's messages, oldest first.
 * @property isLoading Whether the first snapshot has not arrived yet.
 * @property draft The text being typed.
 * @property isSending Whether a message is being sent.
 * @property error Whether loading or sending last failed.
 */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val draft: String = "",
    val isSending: Boolean = false,
    val error: ChatError? = null,
) {
  val canSend: Boolean
    get() = !isSending && Message.isValidText(draft)
}

enum class ChatError {
  LOAD_FAILED,
  SEND_FAILED
}

/** Streams the household chat and sends messages as the current user. */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val householdId: String,
    val userId: String,
    private val userName: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(ChatUiState())
  val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      chatRepository
          .observeMessages(householdId)
          .catch {
            mutableState.update { it.copy(isLoading = false, error = ChatError.LOAD_FAILED) }
          }
          .collect { messages ->
            mutableState.update {
              it.copy(
                  messages = messages,
                  isLoading = false,
                  error = it.error.takeIf { error -> error == ChatError.SEND_FAILED })
            }
          }
    }
  }

  fun onDraftChange(text: String) {
    mutableState.update {
      it.copy(
          draft = text.take(Message.MAX_TEXT_LENGTH),
          error = it.error.takeIf { error -> error == ChatError.LOAD_FAILED })
    }
  }

  fun send() {
    val current = mutableState.value
    if (!current.canSend) return
    val text = current.draft
    mutableState.update { it.copy(isSending = true, error = null) }
    viewModelScope.launch {
      val id =
          try {
            chatRepository.sendMessage(householdId, userId, userName, text)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
      mutableState.update {
        if (id == null) it.copy(isSending = false, error = ChatError.SEND_FAILED)
        else it.copy(isSending = false, draft = if (it.draft == text) "" else it.draft)
      }
    }
  }
}
