// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
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
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Kitchen")
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ABC123").assertIsDisplayed()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    composeTestRule.onNodeWithTag(CreateHouseholdTestTags.BACK).performClick()
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
    gate.complete(Unit)
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

  private fun createViewModel(repository: TestHouseholdRepository = TestHouseholdRepository()) =
      CreateHouseholdViewModel(repository, TestAuthRepository())

  private class TestAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> =
        MutableStateFlow(AuthSession("user-1", "user@example.com"))

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override fun signOut() = Unit
  }

  private class TestHouseholdRepository(var failure: Exception? = null) : HouseholdRepository {
    private val delegate = InMemoryHouseholdRepository()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createHousehold(name: String, creatorId: String): Household {
      gate?.await()
      failure?.let { throw it }
      return Household(name = name, inviteCode = "ABC123", createdBy = creatorId)
    }

    override suspend fun joinHousehold(inviteCode: String, userId: String) =
        delegate.joinHousehold(inviteCode, userId)

    override suspend fun getHousehold(householdId: String) = delegate.getHousehold(householdId)

    override suspend fun getHouseholdForUser(userId: String) = delegate.getHouseholdForUser(userId)
  }
}
