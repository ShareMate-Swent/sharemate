// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.auth

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.android.sharemate.R
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthGateTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun signedInSessionStaysHiddenWhileSignUpIsFinishing() {
    val repository = UiRepository()
    val signUpGate = CompletableDeferred<Unit>()
    repository.signUpGate = signUpGate
    lateinit var viewModel: AuthViewModel
    compose.setContent {
      viewModel = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(viewModel, googleTokenProvider = { null }) {
        Text("Private content", Modifier.testTag("private_content"))
      }
    }

    compose.runOnIdle { repository.session.tryEmit(null) }
    compose.waitUntil { !viewModel.state.value.isRestoringSession }
    compose.runOnIdle {
      viewModel.selectMode(AuthMode.SIGN_UP)
      viewModel.updateEmail("student@example.org")
      viewModel.updatePseudo("Student")
      viewModel.updatePassword("secret")
      viewModel.updateConfirmPassword("secret")
      viewModel.submit()
    }
    compose.waitUntil { repository.signUpStarted }
    compose.runOnIdle { repository.session.tryEmit(AuthSession("user", "student@example.org")) }
    compose.onNodeWithTag(AUTH_GATE_LOADING_TEST_TAG).assertIsDisplayed()
    compose.onNodeWithTag("private_content").assertDoesNotExist()

    compose.runOnIdle { signUpGate.complete(Unit) }
    compose.onNodeWithTag("private_content").assertIsDisplayed()
    compose.onNodeWithTag(AUTH_GATE_LOADING_TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun failedProfileUpdateKeepsSignUpFormUntilRetrySucceeds() {
    val repository = UiRepository()
    val signUpGate = CompletableDeferred<Unit>()
    repository.signUpGate = signUpGate
    repository.signUpFailureAfterSession = IllegalStateException("profile update failed")
    repository.profileUpdateFailure =
        com.android.sharemate.model.auth.AuthException(
            com.android.sharemate.model.auth.AuthError.NETWORK)
    lateinit var viewModel: AuthViewModel
    compose.setContent {
      viewModel = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(viewModel, googleTokenProvider = { null }) {
        Text("Private content", Modifier.testTag("private_content"))
      }
    }

    compose.runOnIdle { repository.session.tryEmit(null) }
    compose.waitUntil { !viewModel.state.value.isRestoringSession }
    compose.runOnIdle {
      viewModel.selectMode(AuthMode.SIGN_UP)
      viewModel.updateEmail("student@example.org")
      viewModel.updatePseudo("Student")
      viewModel.updatePassword("secret")
      viewModel.updateConfirmPassword("secret")
      viewModel.submit()
    }
    compose.waitUntil { repository.signUpStarted }
    compose.runOnIdle {
      repository.session.tryEmit(AuthSession("user", "student@example.org"))
      signUpGate.complete(Unit)
    }

    compose.waitUntil { viewModel.state.value.pendingProfileUpdate != null }
    compose.onNodeWithTag(AUTH_PSEUDO_TEST_TAG).assertIsDisplayed()
    compose.onNodeWithTag(AuthTestTags.ERROR).assertIsDisplayed()
    compose
        .onNodeWithTag(AuthTestTags.SUBMIT)
        .assertTextEquals(compose.activity.getString(R.string.auth_save_pseudo))
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.runOnIdle { repository.profileUpdateFailure = null }
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performClick()
    compose.onNodeWithTag("private_content").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(1, repository.signUpCalls)
      assertEquals(2, repository.profileUpdateCalls)
    }
  }

  @Test
  fun gateHidesContentUntilSessionAndRestoresSystemBarsWhenAuthenticated() {
    val repository = UiRepository()
    lateinit var viewModel: AuthViewModel
    val window = compose.activity.window
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    compose.runOnUiThread {
      window.statusBarColor = Color.BLACK
      window.navigationBarColor = Color.BLACK
      controller.isAppearanceLightStatusBars = false
      controller.isAppearanceLightNavigationBars = false
    }
    compose.setContent {
      viewModel = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(viewModel, googleTokenProvider = { null }) {
        Text("Private content", Modifier.testTag("private_content"))
      }
    }
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.RESTORING).assertIsDisplayed()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(0xFFFEF7FF.toInt(), window.statusBarColor)
      assertEquals(0xFFFEF7FF.toInt(), window.navigationBarColor)
      assertTrue(controller.isAppearanceLightStatusBars)
      assertTrue(controller.isAppearanceLightNavigationBars)
      repository.session.tryEmit(null)
    }
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertExists()
    compose.runOnIdle { repository.session.tryEmit(AuthSession("user", "student@example.org")) }
    compose.onNodeWithTag("private_content").assertIsDisplayed()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(Color.BLACK, window.statusBarColor)
      assertEquals(Color.BLACK, window.navigationBarColor)
      assertFalse(controller.isAppearanceLightStatusBars)
      assertFalse(controller.isAppearanceLightNavigationBars)
      viewModel.signOut()
    }
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
  }

  private class UiRepository : AuthRepository {
    override val session = MutableSharedFlow<AuthSession?>(replay = 1)
    var signUpGate: CompletableDeferred<Unit>? = null
    var signUpStarted = false
    var signUpCalls = 0
    var signUpFailureAfterSession: Exception? = null
    var profileUpdateFailure: Exception? = null
    var profileUpdateCalls = 0

    override suspend fun signIn(email: String, password: String) {}

    override suspend fun signUp(email: String, password: String, displayName: String) {
      signUpCalls++
      signUpStarted = true
      signUpGate?.await()
      signUpFailureAfterSession?.let { throw it }
    }

    override suspend fun updateCurrentUserDisplayName(expectedUid: String, displayName: String) {
      profileUpdateCalls++
      profileUpdateFailure?.let { throw it }
    }

    override suspend fun signInWithGoogle(idToken: String) =
        error("Google sign-in is not used by this fake")

    override fun signOut() {
      session.tryEmit(null)
    }
  }
}
