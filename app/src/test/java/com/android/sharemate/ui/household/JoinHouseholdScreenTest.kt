// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

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
class JoinHouseholdScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun codeBoxesDisplayTypedCharacters() {
    composeTestRule.setContent {
      MaterialTheme { JoinHouseholdScreen(joinViewModel(), onJoined = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("aB12cD")
    composeTestRule.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("B", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("C", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("D", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun loadingShowsIndicatorAndDisablesButton() {
    val repository = TestHouseholdRepository()
    val gate = CompletableDeferred<Unit>()
    repository.gate = gate
    val viewModel = joinViewModel(repository)
    composeTestRule.setContent {
      MaterialTheme { JoinHouseholdScreen(viewModel, onJoined = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("ABC234")
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.JOIN).performClick()
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.LOADING).assertIsDisplayed()
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.JOIN).assertIsNotEnabled()
    gate.complete(Unit)
  }

  @Test
  fun backCallbackIsCalled() {
    var back = 0
    composeTestRule.setContent {
      MaterialTheme { JoinHouseholdScreen(joinViewModel(), onJoined = {}, onBack = { back++ }) }
    }
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.BACK).performClick()
    assertEquals(1, back)
  }

  @Test
  fun joinedCallbackRunsOnceAcrossRecomposition() {
    val viewModel = joinViewModel()
    var joined = 0
    composeTestRule.setContent {
      MaterialTheme { JoinHouseholdScreen(viewModel, onJoined = { joined++ }, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("ABC234")
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.JOIN).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { viewModel.updateInviteCode("ABC123") }
    composeTestRule.waitForIdle()
    assertEquals(1, joined)
  }

  @Test
  fun invalidCodeDisplaysErrorAndShortCodeDisablesButton() {
    val viewModel = joinViewModel(TestHouseholdRepository(IllegalArgumentException("invalid")))
    composeTestRule.setContent {
      MaterialTheme { JoinHouseholdScreen(viewModel, onJoined = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("ABCDE")
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.JOIN).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("F")
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.JOIN).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(JoinHouseholdTestTags.ERROR).assertIsDisplayed()
  }

  private fun joinViewModel(repository: TestHouseholdRepository = TestHouseholdRepository()) =
      JoinHouseholdViewModel(repository, TestAuthRepository())

  private class TestAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> =
        MutableStateFlow(AuthSession("user-1", "user@example.com"))

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override fun signOut() = Unit
  }

  private class TestHouseholdRepository(private val initialFailure: Exception? = null) :
      HouseholdRepository {
    private val delegate = InMemoryHouseholdRepository()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createHousehold(name: String, creatorId: String) =
        delegate.createHousehold(name, creatorId)

    override suspend fun joinHousehold(inviteCode: String, userId: String): Household {
      gate?.await()
      initialFailure?.let { throw it }
      return Household(inviteCode = inviteCode, memberIds = listOf(userId))
    }

    override suspend fun getHousehold(householdId: String) = delegate.getHousehold(householdId)

    override suspend fun getHouseholdForUser(userId: String) = delegate.getHouseholdForUser(userId)
  }
}
