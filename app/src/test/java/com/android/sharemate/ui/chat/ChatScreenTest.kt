// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import android.content.Intent
import android.os.Looper
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.android.sharemate.ui.theme.Dimens
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
  fun reportIncidentButtonOpensTheForm() {
    var opened = 0
    composeTestRule.setContent {
      ChatContent(ChatUiState(isLoading = false), "me", {}, {}, onReportIncident = { opened++ })
    }
    composeTestRule
        .onNodeWithContentDescription(
            composeTestRule.activity.getString(R.string.incident_report_action))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.REPORT_INCIDENT).performClick()
    assertEquals(1, opened)
  }

  @Test
  fun reportIncidentButtonIsHiddenWithoutAnAction() {
    show(ChatUiState(isLoading = false))
    composeTestRule.onAllNodesWithTag(ChatTestTags.REPORT_INCIDENT).assertCountEquals(0)
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
            Message("1", "other", " sam ", "Other"),
            Message("2", "me", "Sam", "Own"),
            Message("3", "other", "Alex", "Renamed"))
    assertEquals(mapOf("other" to 1, "me" to 2), disambiguators(messages))
    show(ChatUiState(isLoading = false, messages = messages))
    composeTestRule.onNodeWithText("sam · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Alex · Member 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam · Member 2").assertDoesNotExist()
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
  fun showsTimeOfEachMessage() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "me", "Alex", "Hello", Date(0)),
                    Message("2", "other", "Sam", "Hi", Date(HOUR)))))
    composeTestRule.onNodeWithText(time(Date(0))).assertIsDisplayed()
    composeTestRule.onNodeWithText(time(Date(HOUR))).assertIsDisplayed()
  }

  @Test
  fun showsEatenActivityWithNameAndTimeButNoItemImage() {
    val message =
        Message("1", "other", "Sam", "", Date(0), MessageType.EATEN, "Milk", "file:///milk")
    show(ChatUiState(isLoading = false, messages = listOf(message)))

    composeTestRule.onNodeWithTag(ChatTestTags.ACTIVITY).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sam ate Milk").assertIsDisplayed()
    composeTestRule.onNodeWithText(time(Date(0))).assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Milk", substring = true).assertDoesNotExist()
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
    composeTestRule.onNodeWithText("Sam threw away Bread").assertIsDisplayed()
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

    composeTestRule.onNodeWithText("Sam · Member 1 ate Milk").assertIsDisplayed()
    composeTestRule.onNodeWithText("sam · Member 2").assertIsDisplayed()
  }

  @Test
  fun showsDefaultSenderNameWhenActivitySenderIsBlank() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(Message(senderName = "", type = MessageType.EATEN, itemName = "Eggs"))))

    composeTestRule.onNodeWithText("Member ate Eggs").assertIsDisplayed()
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
              itemImageUrl: String?,
              activityId: String?,
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
  fun chatRestoresAnUnspecifiedSoftInputModeOnExit() {
    val window = composeTestRule.activity.window
    // An activity without android:windowSoftInputMode starts at 0 (unspecified). Setting 0
    // through Window.setSoftInputMode does not write the attribute, so it needs its own path.
    composeTestRule.runOnUiThread {
      window.attributes = window.attributes.apply { softInputMode = 0 }
    }
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
    assertEquals(0, window.attributes.softInputMode)
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
    composeTestRule.onNodeWithText("First").assertIsDisplayed()
    val firstTop =
        composeTestRule.onNodeWithTag(ChatTestTags.OTHER_MESSAGE).getUnclippedBoundsInRoot().top

    // Simulates the keyboard opening: the chat loses most of its height.
    height = 350.dp
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("First").assertIsDisplayed()
    composeTestRule.onNodeWithText("Second").assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(ChatTestTags.OTHER_MESSAGE)
        .assertTopPositionInRootIsEqualTo(firstTop)
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

  @Test
  fun headerShowsHouseholdNameAndPluralMemberCount() {
    showWithHeader(ChatUiState(isLoading = false), "Casa Rosa", 4)
    composeTestRule.onNodeWithText("Chat").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.SUBTITLE).assertTextEquals("Casa Rosa · 4 members")
  }

  @Test
  fun headerUsesSingularForOneMember() {
    showWithHeader(ChatUiState(isLoading = false), "Studio", 1)
    composeTestRule.onNodeWithTag(ChatTestTags.SUBTITLE).assertTextEquals("Studio · 1 member")
  }

  @Test
  fun headerShowsOnlyCountWhenNameIsBlank() {
    showWithHeader(ChatUiState(isLoading = false), "  ", 2)
    composeTestRule.onNodeWithTag(ChatTestTags.SUBTITLE).assertTextEquals("2 members")
  }

  @Test
  fun headerHidesSubtitleWithoutNameOrMembers() {
    show(ChatUiState(isLoading = false))
    composeTestRule.onNodeWithTag(ChatTestTags.SUBTITLE).assertDoesNotExist()
  }

  @Test
  fun emptyChatShowsInvitationAndNoHeaderAvatars() {
    showWithHeader(ChatUiState(isLoading = false), "Casa Rosa", 4)
    composeTestRule.onNodeWithText("No messages yet. Say hello!").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatTestTags.HEADER_AVATARS).assertDoesNotExist()
  }

  @Test
  fun headerShowsInitialsOfOtherSendersOnly() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "alex", "Alex", "Milk?"),
                    Message("2", "me", "Me", "Later"),
                    Message(
                        "3", "lucia", "Lucía", "", type = MessageType.EATEN, itemName = "Yogurt"))))
    composeTestRule.onNodeWithTag(ChatTestTags.HEADER_AVATARS).assertIsDisplayed()
    val inHeader = hasAnyAncestor(hasTestTag(ChatTestTags.HEADER_AVATARS))
    composeTestRule
        .onAllNodes(hasTestTag(ChatTestTags.AVATAR_INITIALS) and inHeader)
        .assertCountEquals(2)
    val alex = composeTestRule.onNode(hasText("AL") and inHeader).getBoundsInRoot()
    val lucia = composeTestRule.onNode(hasText("LU") and inHeader).getBoundsInRoot()
    // First appearance order: Alex wrote before Lucía.
    assertTrue(alex.left < lucia.left)
  }

  @Test
  fun avatarsShowProfilePhotoWhenAvailable() {
    show(
        ChatUiState(
            isLoading = false,
            messages = listOf(Message("1", "other", "Sam", "Hi")),
            memberPhotos = mapOf("other" to "https://lh3.googleusercontent.com/a/photo")))
    // One avatar in the header and one next to the message.
    composeTestRule.onAllNodesWithTag(ChatTestTags.AVATAR_PHOTO).assertCountEquals(2)
    composeTestRule.onAllNodesWithContentDescription("Photo of Sam").assertCountEquals(2)
  }

  @Test
  fun avatarsFallBackToInitialsWithoutPhoto() {
    show(ChatUiState(isLoading = false, messages = listOf(Message("1", "other", "Sam", "Hi"))))
    composeTestRule.onAllNodesWithTag(ChatTestTags.AVATAR_PHOTO).assertCountEquals(0)
    composeTestRule.onAllNodesWithText("SA").assertCountEquals(2)
  }

  @Test
  fun ownMessagesHaveNoAvatarOrName() {
    show(
        ChatUiState(
            isLoading = false,
            messages = listOf(Message("1", "me", "Alex", "Hello")),
            memberPhotos = mapOf("me" to "https://lh3.googleusercontent.com/a/me")))
    composeTestRule.onAllNodesWithTag(ChatTestTags.AVATAR_PHOTO).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(ChatTestTags.AVATAR_INITIALS).assertCountEquals(0)
    composeTestRule.onNodeWithText("Alex").assertDoesNotExist()
  }

  @Test
  fun consecutiveMessagesShowNameOnceAndOneAvatar() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "other", "Sam", "First"), Message("2", "other", "Sam", "Second"))))
    composeTestRule.onAllNodesWithText("Sam").assertCountEquals(1)
    // One avatar in the header and one next to the last message of the run.
    composeTestRule.onAllNodesWithText("SA").assertCountEquals(2)
  }

  @Test
  fun ownMessagesAndActivitiesAreOnTheRightOthersOnTheLeft() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "me", "Me", "Mine"),
                    Message("2", "other", "Sam", "Theirs"),
                    Message("3", "me", "Me", "", type = MessageType.EATEN, itemName = "Milk"),
                    Message(
                        "4", "other", "Sam", "", type = MessageType.THROWN, itemName = "Kale"))))
    val root = composeTestRule.onNodeWithTag(ChatTestTags.LIST).getBoundsInRoot()
    val middle = (root.left + root.right) / 2
    val own = composeTestRule.onNodeWithTag(ChatTestTags.OWN_MESSAGE).getBoundsInRoot()
    val other = composeTestRule.onNodeWithTag(ChatTestTags.OTHER_MESSAGE).getBoundsInRoot()
    val activities = composeTestRule.onAllNodesWithTag(ChatTestTags.ACTIVITY)
    val ownActivity = activities[0].getBoundsInRoot()
    val otherActivity = activities[1].getBoundsInRoot()

    assertTrue(own.left > middle)
    assertTrue(ownActivity.left > middle)
    assertTrue(other.right < root.right && other.left < middle)
    assertTrue(otherActivity.left < middle)
    // Own rows touch the right padding, the others' activities touch the left padding.
    assertTrue(root.right - own.right <= Dimens.spacingS + 1.dp)
    assertTrue(root.right - ownActivity.right <= Dimens.spacingS + 1.dp)
    assertTrue(otherActivity.left - root.left <= Dimens.spacingS + 1.dp)
  }

  @Test
  fun daySeparatorsShowTodayYesterdayAndOlderDates() {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val older = LocalDate.of(2020, 1, 15)
    fun noon(day: LocalDate) = Date.from(day.atTime(12, 0).atZone(zone).toInstant())
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "other", "Sam", "Old", noon(older)),
                    Message("2", "other", "Sam", "Before", noon(today.minusDays(1))),
                    Message("3", "other", "Sam", "Now", noon(today)),
                    Message("4", "me", "Me", "Pending"))))
    composeTestRule.onAllNodesWithTag(ChatTestTags.DAY_SEPARATOR).assertCountEquals(3)
    composeTestRule
        .onNodeWithText(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(older))
        .assertIsDisplayed()
    composeTestRule.onNodeWithText("Yesterday").assertExists()
    composeTestRule.onNodeWithText("Today").assertExists()
  }

  @Test
  fun daySeparatorIsACenteredBadge() {
    show(ChatUiState(isLoading = false, messages = listOf(Message("1", "other", "Sam", "Hi"))))
    val list = composeTestRule.onNodeWithTag(ChatTestTags.LIST).getBoundsInRoot()
    val badge = composeTestRule.onNodeWithTag(ChatTestTags.DAY_SEPARATOR).getBoundsInRoot()
    val listCenter = (list.left + list.right) / 2
    val badgeCenter = (badge.left + badge.right) / 2
    assertTrue(abs(listCenter.value - badgeCenter.value) <= 1f)
    // A badge, not a full-width row.
    assertTrue(badge.right - badge.left < (list.right - list.left) / 2)
  }

  @Test
  fun todayBecomesYesterdayAtMidnightWhileTheChatIsOpen() {
    val zone = ZoneId.of("Europe/Zurich")
    var now = ZonedDateTime.of(2026, 11, 24, 23, 59, 0, 0, zone)
    val sent = Date.from(now.minusHours(1).toInstant())
    composeTestRule.setContent {
      ChatContent(
          ChatUiState(
              isLoading = false, messages = listOf(Message("1", "other", "Sam", "Hi", sent))),
          "me",
          onDraftChange = {},
          onSend = {},
          clock = { now })
    }
    composeTestRule.onNodeWithText("Today").assertExists()

    now = now.plusMinutes(2)
    composeTestRule.activity.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
    shadowOf(Looper.getMainLooper()).idle()

    composeTestRule.onNodeWithText("Yesterday").assertExists()
    composeTestRule.onNodeWithText("Today").assertDoesNotExist()
  }

  @Test
  fun dayThatReappearsAfterAPendingMessageDoesNotCrash() {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun at(day: LocalDate, hour: Int) = Date.from(day.atTime(hour, 0).atZone(zone).toInstant())
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "other", "Sam", "Old", at(today.minusDays(1), 12)),
                    Message("2", "other", "Sam", "New", at(today, 12)),
                    // Pending: sorted last, estimated before midnight.
                    Message("3", "me", "Me", "Late", at(today.minusDays(1), 23)))))
    composeTestRule.onAllNodesWithTag(ChatTestTags.DAY_SEPARATOR).assertCountEquals(3)
    composeTestRule.onNodeWithText("Late").assertIsDisplayed()
  }

  @Test
  fun eatenAndThrownUseTheirOwnIcons() {
    show(
        ChatUiState(
            isLoading = false,
            messages =
                listOf(
                    Message("1", "other", "Sam", "", type = MessageType.EATEN, itemName = "Milk"),
                    Message(
                        "2", "other", "Sam", "", type = MessageType.THROWN, itemName = "Kale"))))
    composeTestRule.onNodeWithText("Sam ate Milk").assertExists()
    composeTestRule.onNodeWithText("Sam threw away Kale").assertExists()
    composeTestRule.onAllNodesWithTag(ChatTestTags.ACTIVITY).assertCountEquals(2)
  }

  @Test
  fun screenPassesHouseholdToHeader() {
    val viewModel = ChatViewModel(EmptyChatRepository, "h", "me", "Alex")
    composeTestRule.setContent {
      ChatScreen(viewModel, householdName = "Casa Rosa", memberCount = 3)
    }
    composeTestRule.onNodeWithTag(ChatTestTags.SUBTITLE).assertTextEquals("Casa Rosa · 3 members")
  }

  private fun showWithHeader(state: ChatUiState, householdName: String, memberCount: Int) {
    composeTestRule.setContent {
      ChatContent(
          state,
          "me",
          onDraftChange = {},
          onSend = { sends++ },
          householdName = householdName,
          memberCount = memberCount)
    }
  }

  private fun time(date: Date): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)

  private object EmptyChatRepository : ChatRepository {
    override fun observeMessages(householdId: String): Flow<List<Message>> =
        MutableStateFlow(emptyList())

    override suspend fun sendMessage(
        householdId: String,
        senderId: String,
        senderName: String,
        text: String
    ): String? = null

    override suspend fun sendActivity(
        householdId: String,
        senderId: String,
        senderName: String,
        type: MessageType,
        itemName: String,
        itemImageUrl: String?,
        activityId: String?,
    ): String? = null

    override suspend fun sendIncident(
        householdId: String,
        senderId: String,
        senderName: String,
        description: String,
        photo: IncidentPhotoReference
    ): String? = null
  }

  private companion object {
    const val HOUR = 3_600_000L
  }
}
