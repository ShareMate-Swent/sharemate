// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var sends = 0

  private fun show(state: ChatUiState) {
    composeTestRule.setContent {
      ChatContent(state, "me", onDraftChange = {}, onSend = { sends++ })
    }
  }

  @Test
  fun showsLoading() {
    show(ChatUiState())
    composeTestRule.onNodeWithTag(ChatTestTags.LOADING).assertIsDisplayed()
  }

  @Test
  fun showsEmptyState() {
    show(ChatUiState(isLoading = false))
    composeTestRule.onNodeWithTag(ChatTestTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun distinguishesOwnAndOtherMessages() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "other", "Sam", "Hi there"), Message("2", "me", "Alex", "Hello"))))
    composeTestRule.onNodeWithText("Hi there").assertIsDisplayed()
    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam").assertIsDisplayed()
    assertEquals(
        1, composeTestRule.onAllNodesWithTag(ChatTestTags.OWN_MESSAGE).fetchSemanticsNodes().size)
    assertEquals(
        1, composeTestRule.onAllNodesWithTag(ChatTestTags.OTHER_MESSAGE).fetchSemanticsNodes().size)
  }

  @Test
  fun sendButtonFollowsDraftAndInvokesCallback() {
    show(ChatUiState(isLoading = false, draft = "yo"))
    composeTestRule.onNodeWithTag(ChatTestTags.SEND).assertIsEnabled().performClick()
    assertEquals(1, sends)
  }

  @Test
  fun sendDisabledWhenDraftBlank() {
    show(ChatUiState(isLoading = false))
    composeTestRule.onNodeWithTag(ChatTestTags.SEND).assertIsNotEnabled()
  }

  @Test
  fun showsSendError() {
    show(ChatUiState(isLoading = false, error = ChatError.SEND_FAILED))
    composeTestRule.onNodeWithTag(ChatTestTags.ERROR).assertIsDisplayed()
  }

  @Test
  fun showsLoadError() {
    show(ChatUiState(isLoading = false, error = ChatError.LOAD_FAILED))
    composeTestRule.onNodeWithText("Could not load messages.").assertIsDisplayed()
  }

  @Test
  fun showsTimestampWhenPresent() {
    show(
        ChatUiState(
            isLoading = false, messages = listOf(Message("1", "me", "Alex", "Hello", Date(0)))))
    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
  }

  @Test
  fun screenBindsViewModel() {
    val flow = MutableStateFlow(listOf(Message("1", "other", "Sam", "Hi")))
    val sent = mutableListOf<String>()
    val repository =
        object : ChatRepository {
          override fun observeMessages(householdId: String): Flow<List<Message>> = flow

          override suspend fun sendMessage(
              householdId: String,
              senderId: String,
              senderName: String,
              text: String
          ): String? {
            sent += text
            return "id"
          }
        }
    val viewModel = ChatViewModel(repository, "h", "me", "Alex")
    composeTestRule.setContent { ChatScreen(viewModel) }
    composeTestRule.onNodeWithText("Hi").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.INPUT).performTextInput("yo")
    composeTestRule.onNodeWithTag(ChatTestTags.SEND).performClick()
    composeTestRule.waitForIdle()
    assertEquals(listOf("yo"), sent)
  }
}
