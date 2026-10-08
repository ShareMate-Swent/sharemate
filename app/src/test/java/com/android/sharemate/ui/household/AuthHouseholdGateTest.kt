package com.android.sharemate.ui.household

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.theme.SampleAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AuthHouseholdGateTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = SessionRepository()
  private val households = MembershipRepository()

  @Test
  fun newSignupRequiresHouseholdAndConfirmedCreationSurvivesActivityRecreation() {
    auth.session.value = null
    val content: @Composable () -> Unit = { GateContent() }
    compose.setContent(content)
    compose.onNodeWithTag("auth_welcome_signup").performScrollTo().performClick()
    compose.onNodeWithTag("auth_email").performTextInput("student@example.org")
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput("secret")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag("private_app").assertDoesNotExist()
    startCreation()
    compose.onNodeWithText("ABC123").assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.activityRule.scenario.onActivity {
      it.setContent(content = content)
      it.window.decorView.requestLayout()
    }
    compose.waitForIdle()
    compose.onNodeWithText("ABC123").assertIsDisplayed()
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).assertDoesNotExist()
    compose.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    compose.onNodeWithTag("private_app").assertIsDisplayed()
    assertEquals(listOf("student", "student"), households.lookups)
  }

  @Test
  fun systemBackWaitsForCreationAndThenRefreshesMembership() {
    val pending = CompletableDeferred<Unit>()
    households.creationPending = pending
    compose.setContent { GateContent() }
    startCreation()
    compose.onNodeWithTag(CreateHouseholdTestTags.LOADING).assertExists()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).assertExists()
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).assertDoesNotExist()
    compose.runOnIdle { pending.complete(Unit) }
    compose.onNodeWithText("ABC123").assertIsDisplayed()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag("private_app").assertIsDisplayed()
    compose.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).assertDoesNotExist()
    assertEquals(listOf("student", "student"), households.lookups)
  }

  @Test
  fun joinBackReturnsToChoiceAndSuccessfulJoinRefreshesMembership() {
    compose.setContent { GateContent() }
    compose.onNodeWithTag(HouseholdChoiceTestTags.JOIN).performClick()
    compose.onNodeWithTag(JoinHouseholdTestTags.BACK).performClick()
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).assertIsDisplayed()
    compose.onNodeWithTag(HouseholdChoiceTestTags.JOIN).performClick()
    compose.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput("JOIN12")
    compose.onNodeWithTag(JoinHouseholdTestTags.JOIN).performClick()
    compose.onNodeWithTag("private_app").assertIsDisplayed()
    compose.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).assertDoesNotExist()
    assertEquals(listOf("student", "student", "student"), households.lookups)
  }

  private fun startCreation() {
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).performClick()
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("Our home")
    compose.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
  }

  @Test
  fun retryAndAccountChangeCancelOldLookupWithoutRevealingOldHousehold() {
    households.failure = IllegalStateException("Offline")
    compose.setContent { GateContent() }
    compose.onNodeWithTag("private_app").assertDoesNotExist()
    compose.onNodeWithText("Something went wrong. Please try again.").assertIsDisplayed()
    val pending = CompletableDeferred<Unit>()
    compose.runOnIdle {
      households.failure = null
      households.pending["student"] = pending
    }
    compose.onNodeWithTag("household_retry").performClick()
    compose.onNodeWithTag("household_loading").assertExists()
    compose.onNodeWithTag("private_app").assertDoesNotExist()
    compose.runOnIdle { auth.session.value = AuthSession("other-user", "other@example.org") }
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(listOf("student"), households.cancelled)
      households.memberships["student"] = Household(id = "old-home")
      pending.complete(Unit)
    }
    compose.onNodeWithTag("private_app").assertDoesNotExist()
    compose.onNodeWithTag(HouseholdChoiceTestTags.JOIN).assertIsDisplayed()
    assertEquals(listOf("student", "student", "other-user"), households.lookups)
    compose.onNodeWithTag("household_sign_out").performClick()
    compose.onNodeWithTag("auth_welcome").assertExists()
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).assertDoesNotExist()
  }

  @Composable
  private fun GateContent() {
    val authViewModel: AuthViewModel = viewModel { AuthViewModel(auth) }
    SampleAppTheme(dynamicColor = false) {
      AuthHouseholdGate(authViewModel, auth, households) {
        Text("Private application", Modifier.testTag("private_app"))
      }
    }
  }

  private class SessionRepository : AuthRepository {
    override val session =
        MutableStateFlow<AuthSession?>(AuthSession("student", "student@example.org"))

    override suspend fun signIn(email: String, password: String) {
      session.value = AuthSession("student", email)
    }

    override suspend fun signUp(email: String, password: String) = signIn(email, password)

    override fun signOut() {
      session.value = null
    }
  }

  private class MembershipRepository : HouseholdRepository by InMemoryHouseholdRepository() {
    val lookups = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val memberships = mutableMapOf<String, Household>()
    val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
    var failure: Exception? = null
    var creationPending: CompletableDeferred<Unit>? = null

    override suspend fun getHouseholdForUser(userId: String): Household? {
      lookups.add(userId)
      try {
        pending[userId]?.await()
        failure?.let { throw it }
        return memberships[userId]
      } catch (cancelled: CancellationException) {
        this.cancelled.add(userId)
        throw cancelled
      }
    }

    override suspend fun createHousehold(name: String, creatorId: String): Household {
      creationPending?.await()
      return Household(
              id = "created-home",
              name = name,
              inviteCode = "ABC123",
              memberIds = listOf(creatorId))
          .also { memberships[creatorId] = it }
    }

    override suspend fun joinHousehold(inviteCode: String, userId: String): Household =
        Household(id = "joined-home", inviteCode = inviteCode, memberIds = listOf(userId)).also {
          memberships[userId] = it
        }
  }
}
