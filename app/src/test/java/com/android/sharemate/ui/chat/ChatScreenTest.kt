// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.model.incident.IncidentPhotoReference
import java.text.DateFormat
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

  private fun disambiguators(messages: List<Message>): Map<String, Int> =
      senderDisambiguators(messages, "Member") { name, number ->
        composeTestRule.activity.getString(R.string.chat_sender_identity, name, number)
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
  fun identicalNamesShowDifferentMemberNumbersWithoutExposingIds() {
    val firstId = "Xk3abcdefghijABCDEFGHIJKLMNO"
    val secondId = "Xk3abcdefghijABCDEFGHIJKLMNP"
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", firstId, "Sam", "First"),
                    Message("2", secondId, "Sam", "Second"))))
    composeTestRule.onNodeWithText("Sam · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam · Member 2").assertIsDisplayed()
    composeTestRule.onNodeWithText(firstId, substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText(secondId, substring = true).assertDoesNotExist()
  }

  @Test
  fun literalGeneratedLabelDoesNotCollideWithAnotherSender() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "a", "Sam", "First"),
                    Message("2", "b", "Sam", "Second"),
                    Message("3", "c", "Sam · Member 1", "Third"))))
    composeTestRule.onNodeWithText("Sam · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam · Member 2").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam · Member 1 · Member 3").assertIsDisplayed()
  }

  @Test
  fun generatedLabelCollisionsAreResolvedTransitively() {
    val messages =
        listOf(
            Message("1", "a", "Sam", "First"),
            Message("2", "b", "Sam", "Second"),
            Message("3", "c", "Sam · Member 1", "Third"),
            Message("4", "d", "Sam · Member 1 · Member 3", "Fourth"))
    assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4), disambiguators(messages))
  }

  @Test
  fun historicalNamesDoNotConflictWithTheSameAccount() {
    assertEquals(
        emptyMap<String, Int>(),
        disambiguators(
            listOf(
                Message("1", "a", "Sam", "First"),
                Message("2", "a", "Alex", "Second"),
                Message("3", "a", "Sam", "Third"))))
  }

  @Test
  fun disambiguatesHistoricalNamesAndOwnMessages() {
    val messages =
        listOf(
            Message("1", "me", "Sam", "Own"),
            Message("2", "other", " sam ", "Other"),
            Message("3", "other", "Alex", "Renamed"))
    assertEquals(mapOf("me" to 1, "other" to 2), disambiguators(messages))
    show(ChatUiState(isLoading = false, messages = messages))
    composeTestRule.onNodeWithText("sam · Member 2").assertIsDisplayed()
    composeTestRule.onNodeWithText("Alex · Member 2").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam · Member 1").assertDoesNotExist()
  }

  @Test
  fun blankAndEmailNamesUseDefaultAndAreDisambiguated() {
    val messages =
        listOf(
            Message("1", "a", "", "First"),
            Message("2", "b", "person@example.com", "Second"),
            Message("3", "c", "Member", "Third"))
    assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), disambiguators(messages))
    show(ChatUiState(isLoading = false, messages = messages))
    composeTestRule.onNodeWithText("Member · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Member · Member 2").assertIsDisplayed()
    composeTestRule.onNodeWithText("Member · Member 3").assertIsDisplayed()
    composeTestRule.onNodeWithText("person@example.com", substring = true).assertDoesNotExist()
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
    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.chat_load_error))
        .assertIsDisplayed()
  }

  @Test
  fun showsTimestampWhenPresent() {
    show(
        ChatUiState(
            isLoading = false, messages = listOf(Message("1", "me", "Alex", "Hello", Date(0)))))
    composeTestRule
        .onNodeWithText(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(0)))
        .assertIsDisplayed()
  }

  @Test
  fun showsEatenActivityBadgeWithItemImageAndTimestamp() {
    val message =
        Message("1", "other", "Sam", "", Date(0), MessageType.EATEN, "Milk", "file:///milk")
    show(ChatUiState(isLoading = false, messages = listOf(message)))

    composeTestRule.onNodeWithTag(ChatTestTags.ACTIVITY).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ate Milk").assertIsDisplayed()
    composeTestRule
        .onNodeWithText(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(0)))
        .assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Image of Milk").assertIsDisplayed()
  }

  @Test
  fun showsThrownActivityBadgeWithActionSpecificLabel() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message(
                        "1", "other", "Sam", "", type = MessageType.THROWN, itemName = "Bread"))))

    composeTestRule.onNodeWithTag(ChatTestTags.ACTIVITY).assertIsDisplayed()
    composeTestRule.onNodeWithText("Threw away Bread").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Image of Bread").assertDoesNotExist()
  }

  @Test
  fun activityBadgeUsesDisambiguatedSenderName() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message(
                        "1", "activity", "Sam", "", type = MessageType.EATEN, itemName = "Milk"),
                    Message("2", "member", "sam", "Hello"))))

    composeTestRule.onNodeWithText("Sam · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("sam · Member 2").assertIsDisplayed()
  }

  @Test
  fun showsDefaultSenderNameWhenActivitySenderIsBlank() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(Message(senderName = "", type = MessageType.EATEN, itemName = "Eggs"))))

    composeTestRule.onNodeWithText("Member").assertIsDisplayed()
  }

  @Test
  fun loadErrorHidesEmptyState() {
    show(ChatUiState(isLoading = false, error = ChatError.LOAD_FAILED))
    composeTestRule.onNodeWithTag(ChatTestTags.EMPTY).assertDoesNotExist()
  }

  @Test
  fun screenBindsViewModel() {
    val flow = MutableStateFlow(listOf(Message("1", "other", "Sam", "Hi")))
    val sent = mutableListOf<String>()
    val repository =
        object : ChatRepository {
          override fun observeMessages(householdId: String): Flow<List<Message>> = flow

          override suspend fun sendActivity(
              householdId: String,
              senderId: String,
              senderName: String,
              type: MessageType,
              itemName: String,
              itemImageUrl: String?
          ): String? = null

          override suspend fun sendIncident(
              householdId: String,
              senderId: String,
              senderName: String,
              description: String,
              photo: IncidentPhotoReference
          ): String? = null

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

  @Test
  fun incidentShowsItsDescriptionInABubble() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message(
                        "1",
                        "member",
                        "Sam",
                        "The washing machine is leaking",
                        type = MessageType.INCIDENT))))

    composeTestRule.onNodeWithText("The washing machine is leaking").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.OTHER_MESSAGE).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.ACTIVITY).assertDoesNotExist()
  }

  @Test
  fun withAdjustResizeReplacesOnlyTheAdjustFlags() {
    val mode = LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_HIDDEN
    assertEquals(
        LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        withAdjustResize(mode))
  }

  @Test
  fun chatResizesWindowForKeyboardAndRestoresPreviousModeOnExit() {
    val window = composeTestRule.activity.window
    composeTestRule.runOnUiThread { window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN) }
    var shown by mutableStateOf(true)
    composeTestRule.setContent {
      if (shown) ChatContent(ChatUiState(isLoading = false), "me", {}, {})
    }
    composeTestRule.waitForIdle()
    assertEquals(
        LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST)

    shown = false
    composeTestRule.waitForIdle()
    assertEquals(
        LayoutParams.SOFT_INPUT_ADJUST_PAN,
        window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
  }

  @Test
  fun messagesThatFitStayAtTheTopWhenTheChatShrinks() {
    var height by mutableStateOf(600.dp)
    composeTestRule.setContent {
      Box(Modifier.height(height)) {
        ChatContent(
            ChatUiState(
                isLoading = false,
                messages =
                    listOf(
                        Message("1", "other", "Sam", "First"), Message("2", "me", "A", "Second"))),
            "me",
            {},
            {})
      }
    }
    val listTop = composeTestRule.onNodeWithTag(ChatTestTags.LIST).getUnclippedBoundsInRoot().top
    composeTestRule.onNodeWithText("First").assertIsDisplayed()

    // Simulates the keyboard opening: the chat loses most of its height.
    height = 350.dp
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("First").assertIsDisplayed()
    composeTestRule.onNodeWithText("Second").assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(ChatTestTags.OTHER_MESSAGE)
        .assertTopPositionInRootIsEqualTo(listTop)
  }

  @Test
  fun chatRendersWithoutAnActivityContext() {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalContext provides composeTestRule.activity.applicationContext) {
        ChatContent(ChatUiState(isLoading = false), "me", {}, {})
      }
    }
    composeTestRule.onNodeWithTag(ChatTestTags.EMPTY).assertIsDisplayed()
  }
}
