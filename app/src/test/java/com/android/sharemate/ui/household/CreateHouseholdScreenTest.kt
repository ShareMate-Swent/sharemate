// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CreateHouseholdScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun validNameDisplaysCodeAndButtonsWork() {
    val repository = TestHouseholdRepository()
    val viewModel = createViewModel(repository)
    var back = 0
    var continueCount = 0
    composeTestRule.setContent {
      MaterialTheme {
        CreateHouseholdScreen(
            viewModel,
            onContinue = { continueCount++ },
            onBack = { back++ },
        )
      }
    }
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.BACK).performClick()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.TYPE_SHARED).assertIsSelected()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.TYPE_PRIVATE).assertIsNotSelected()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Kitchen")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ABC123").assertIsDisplayed()
    // The confirmation has no back arrow: the household already exists at this point.
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.BACK).assertDoesNotExist()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.COPY_INVITE_CODE).performClick()
    assertEquals(1, continueCount)
    assertEquals(1, back)
    val clipboard =
        composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
    assertEquals("ABC123", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
  }

  @Test
  fun blankNameDisablesCreate() {
    composeTestRule.setContent {
      MaterialTheme { CreateHouseholdScreen(createViewModel(), onContinue = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).assertIsNotEnabled()
  }

  @Test
  fun compactHeightAllowsScrollingToSelectTypeAndCreate() {
    val viewModel = createViewModel()
    composeTestRule.setContent {
      MaterialTheme {
        Box(Modifier.height(320.dp)) {
          CreateHouseholdScreen(viewModel, onContinue = {}, onBack = {})
        }
      }
    }
    composeTestRule
        .onNodeWithTag(CreateHouseholdTestTags.NAME)
        .performScrollTo()
        .performTextInput("My fridge")
    composeTestRule
        .onNodeWithTag(CreateHouseholdTestTags.TYPE_PRIVATE)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    composeTestRule
        .onNodeWithTag(CreateHouseholdTestTags.CREATE)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    composeTestRule.waitForIdle()

    assertEquals(FridgeType.PRIVATE, viewModel.state.value.type)
    assertEquals("ABC123", viewModel.state.value.inviteCode)
  }

  @Test
  fun loadingIndicatorIsShownWhileCreating() {
    val repository = TestHouseholdRepository()
    val gate = CompletableDeferred<Unit>()
    repository.gate = gate
    val viewModel = createViewModel(repository)
    composeTestRule.setContent {
      MaterialTheme { CreateHouseholdScreen(viewModel, onContinue = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Kitchen")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.LOADING).assertIsDisplayed()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.TYPE_PRIVATE).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.TYPE_SHARED).assertIsNotEnabled()
    gate.complete(Unit)
  }

  @Test
  fun selectingPrivateShowsPrivateConfirmationWithoutInviteActions() {
    var continued = 0
    composeTestRule.setContent {
      MaterialTheme {
        CreateHouseholdScreen(createViewModel(), onContinue = { continued++ }, onBack = {})
      }
    }
    composeTestRule
        .onNodeWithTag(CreateHouseholdTestTags.TYPE_PRIVATE)
        .performClick()
        .assertIsSelected()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.TYPE_SHARED).assertIsNotSelected()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("My fridge")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Your private fridge is ready!").assertIsDisplayed()
    composeTestRule.onNodeWithText("Private", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("ABC123").assertDoesNotExist()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.COPY_INVITE_CODE).assertDoesNotExist()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.SHARE_INVITE_CODE).assertDoesNotExist()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    assertEquals(1, continued)
  }

  @Test
  fun failureCanBeRetriedSuccessfully() {
    val repository = TestHouseholdRepository(IllegalStateException("failure"))
    val viewModel = createViewModel(repository)
    composeTestRule.setContent {
      MaterialTheme { CreateHouseholdScreen(viewModel, onContinue = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Kitchen")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.ERROR).assertIsDisplayed()
    repository.failure = null
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ABC123").assertIsDisplayed()
  }

  @Test
  fun shareOpensChooserWithInviteCode() {
    composeTestRule.setContent {
      MaterialTheme { CreateHouseholdScreen(createViewModel(), onContinue = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Kitchen")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.SHARE_INVITE_CODE).performClick()

    val chooser = shadowOf(composeTestRule.activity).nextStartedActivity
    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
    val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    assertEquals(Intent.ACTION_SEND, send?.action)
    assertEquals("text/plain", send?.type)
    assertEquals("ABC123", send?.getStringExtra(Intent.EXTRA_TEXT))
  }

  private fun createViewModel(repository: TestHouseholdRepository = TestHouseholdRepository()) =
      CreateHouseholdViewModel(repository, TestAuthRepository())

  private class TestAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> =
        MutableStateFlow(AuthSession("user-1", "user@example.com"))

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) =
        error("Google sign-in is not used by this fake")

    override fun signOut() = Unit
  }

  private class TestHouseholdRepository(var failure: Exception? = null) : HouseholdRepository {
    private val delegate = InMemoryHouseholdRepository()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createHousehold(
        name: String,
        creatorId: String,
        type: FridgeType
    ): Household {
      gate?.await()
      failure?.let { throw it }
      return Household(name = name, inviteCode = "ABC123", createdBy = creatorId, type = type)
    }

    override suspend fun joinHousehold(inviteCode: String, userId: String) =
        delegate.joinHousehold(inviteCode, userId)

    override suspend fun leaveHousehold(userId: String) = delegate.leaveHousehold(userId)

    override suspend fun getHousehold(householdId: String) = delegate.getHousehold(householdId)

    override suspend fun getHouseholdForUser(userId: String) = delegate.getHouseholdForUser(userId)
  }
}
