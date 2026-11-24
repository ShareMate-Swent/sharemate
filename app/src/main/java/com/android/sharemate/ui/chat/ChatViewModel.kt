// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.profile.supportedPhotoUrl
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
 * @property memberPhotos Profile photo URL of each household member who has a supported one, by
 *   uid.
 */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val draft: String = "",
    val isSending: Boolean = false,
    val error: ChatError? = null,
    val memberPhotos: Map<String, String> = emptyMap(),
) {
  val canSend: Boolean
    get() = !isSending && Message.isValidText(draft)
}

enum class ChatError {
  LOAD_FAILED,
  SEND_FAILED
}

/**
 * Streams the household chat and sends messages as the current user.
 *
 * It also follows the members' profiles to show their photos next to their messages. Photos are
 * optional: messages never wait for them, and a profile failure only leaves the photos out. Photos
 * from sources ShareMate does not support are dropped (see [supportedPhotoUrl]).
 *
 * @param profileRepository Source of the members' photos; null shows no photos.
 * @param photoBucket Storage bucket of uploaded profile photos; null keeps Google photos only.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val householdId: String,
    val userId: String,
    private val userName: String,
    private val profileRepository: UserProfileRepository? = null,
    private val photoBucket: String? = null,
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
    profileRepository?.let(::observePhotos)
  }

  private fun observePhotos(repository: UserProfileRepository) {
    viewModelScope.launch {
      try {
        repository.observeMembers(householdId).collect { profiles ->
          val photos =
              profiles
                  .mapNotNull { profile ->
                    supportedPhotoUrl(profile.photoUrl, profile.uid, photoBucket)?.let {
                      profile.uid to it
                    }
                  }
                  .toMap()
          mutableState.update { it.copy(memberPhotos = photos) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Photos are decorative: avatars fall back to initials and the chat stays usable.
      }
    }
  }

  /** Updates the draft, cut to [Message.MAX_TEXT_LENGTH], and clears a previous send error. */
  fun onDraftChange(text: String) {
    mutableState.update {
      it.copy(
          draft = text.take(Message.MAX_TEXT_LENGTH),
          error = it.error.takeIf { error -> error == ChatError.LOAD_FAILED })
    }
  }

  /** Sends the draft if it is valid and no message is being sent; keeps it if sending fails. */
  fun send() {
    val current = mutableState.value
    if (!current.canSend) return
    val text = current.draft
    mutableState.update { it.copy(isSending = true, error = null) }
    viewModelScope.launch {
      try {
        val id = chatRepository.sendMessage(householdId, userId, userName, text)
        mutableState.update {
          if (id == null) it.copy(error = ChatError.SEND_FAILED)
          else it.copy(draft = if (it.draft == text) "" else it.draft)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = ChatError.SEND_FAILED) }
      } finally {
        mutableState.update { it.copy(isSending = false) }
      }
    }
  }
}
