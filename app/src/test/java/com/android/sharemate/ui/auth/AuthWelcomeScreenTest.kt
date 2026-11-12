// Co-authored-by: Codex
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthWelcomeScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun welcomeChoosesFormsAndBackClearsSecretsAndErrors() {
    val repository = WelcomeRepository()
    lateinit var vm: AuthViewModel
    compose.setContent {
      vm = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(vm, googleTokenProvider = { null }) { Text("Private content") }
    }
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
    compose.onNodeWithTag(AuthTestTags.GOOGLE_SIGN_IN).assertExists()
    compose.onNodeWithTag(AuthTestTags.WELCOME_SIGNUP).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).assertExists()
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextInput("student@example.org")
    compose.onNodeWithTag(AUTH_PSEUDO_TEST_TAG).performScrollTo().performTextInput("Student")
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose
        .onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD)
        .performScrollTo()
        .performTextInput("different")
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    compose
        .onNodeWithTag(AuthTestTags.ERROR)
        .assertTextEquals(compose.activity.getString(R.string.auth_error_password_mismatch))
    compose.onNodeWithTag(AuthTestTags.BACK).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
    compose.runOnIdle {
      assertEquals("", vm.state.value.password)
      assertEquals("", vm.state.value.confirmPassword)
      assertNull(vm.state.value.error)
      assertTrue(repository.calls.isEmpty())
    }
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).assertDoesNotExist()
    compose
        .onNodeWithTag(AuthTestTags.EMAIL)
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("student@example.org")))
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.BACK).assertIsNotEnabled()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.LOADING).assertExists()
    compose.runOnIdle {
      assertEquals(listOf("student@example.org" to "secret"), repository.calls)
      repository.pending.completeExceptionally(AuthException(AuthError.NETWORK))
    }
    compose
        .onNodeWithTag(AuthTestTags.ERROR)
        .assertTextEquals(compose.activity.getString(R.string.auth_error_network))
    compose.onNodeWithTag(AuthTestTags.BACK).assertIsEnabled().performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
  }

  @Test
  fun successfulRequestKeepsFormLockedUntilSessionArrives() {
    val repository = WelcomeRepository()
    compose.setContent {
      val vm = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(vm, googleTokenProvider = { null }) { Text("Private content") }
    }
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextInput("student@example.org")
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    compose.runOnIdle { repository.pending.complete(Unit) }
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.BACK).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.LOADING).assertExists()
    compose.onNodeWithText("Private content").assertDoesNotExist()
    compose.runOnIdle { repository.session.value = AuthSession("user", "student@example.org") }
    compose.onNodeWithText("Private content").assertIsDisplayed()
    compose.onNodeWithTag(AuthTestTags.LOADING).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertDoesNotExist()
  }

  @Test
  fun compactLandscapeAllowsScrollingToBothActions() {
    var loginClicks = 0
    var signupClicks = 0
    compose.setContent {
      Box(Modifier.requiredSize(640.dp, 320.dp)) {
        AuthWelcomeScreen({ loginClicks++ }, { signupClicks++ })
      }
    }
    compose
        .onNodeWithTag(AuthTestTags.WELCOME_LOGIN)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose
        .onNodeWithTag(AuthTestTags.WELCOME_SIGNUP)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, loginClicks)
      assertEquals(1, signupClicks)
    }
  }

  @Test
  fun googleButtonShowsSafeFailureAndDisablesAllWelcomeActionsWhileLoading() {
    val loading = androidx.compose.runtime.mutableStateOf(false)
    var calls = 0
    compose.setContent {
      AuthWelcomeScreen(
          {},
          {},
          onGoogleSignIn = { calls++ },
          isLoading = loading.value,
          error = AuthUiError.GOOGLE_UNAVAILABLE)
    }
    compose
        .onNodeWithTag(AuthTestTags.GOOGLE_SIGN_IN)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, calls)
      loading.value = true
    }
    compose.onNodeWithTag(AuthTestTags.GOOGLE_SIGN_IN).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.WELCOME_SIGNUP).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.GOOGLE_LOADING).assertExists()
    compose.runOnIdle { loading.value = false }
    compose.onNodeWithTag(AuthTestTags.GOOGLE_LOADING).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.GOOGLE_SIGN_IN).assertIsEnabled()
    compose.onNodeWithTag(AuthTestTags.ERROR).assertExists()
  }

  @Test
  fun googleLoginOpensPrivateContentOnlyAfterFirebaseSessionAndReturnsToWelcomeOnLogout() {
    val repository = WelcomeRepository()
    lateinit var vm: AuthViewModel
    compose.setContent {
      vm = androidx.lifecycle.viewmodel.compose.viewModel { AuthViewModel(repository) }
      AuthGate(vm, googleTokenProvider = { "google-token" }) { Text("Private content") }
    }
    compose.onNodeWithTag(AuthTestTags.GOOGLE_SIGN_IN).performScrollTo().performClick()
    compose.runOnIdle { repository.pending.complete(Unit) }
    compose.onNodeWithTag(AuthTestTags.GOOGLE_LOADING).assertExists()
    compose.onNodeWithText("Private content").assertDoesNotExist()
    compose.runOnIdle {
      repository.session.value =
          AuthSession("google-user", "student@example.org", displayName = "Google User")
    }
    compose.onNodeWithText("Private content").assertIsDisplayed()
    compose.runOnIdle { vm.signOut() }
    compose.onNodeWithText("Private content").assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
  }

  private class WelcomeRepository : AuthRepository {
    override val session = MutableStateFlow<AuthSession?>(null)
    val calls = mutableListOf<Pair<String, String>>()
    val pending = CompletableDeferred<Unit>()

    override suspend fun signInWithGoogle(idToken: String) {
      assertEquals("google-token", idToken)
      pending.await()
    }

    override suspend fun signIn(email: String, password: String) {
      calls.add(email to password)
      pending.await()
    }

    override suspend fun signUp(email: String, password: String, displayName: String) =
        error("Invalid signup reached repository")

    override suspend fun updateCurrentUserDisplayName(expectedUid: String, displayName: String) =
        Unit

    override fun signOut() {
      session.value = null
    }
  }
}
