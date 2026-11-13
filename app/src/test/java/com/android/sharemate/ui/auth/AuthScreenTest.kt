// Co-authored-by: Codex
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.android.sharemate.R
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.user.MAX_PSEUDO_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AuthScreenTest {
  @get:Rule val compose = createComposeRule()

  private val testApplication
    get() = RuntimeEnvironment.getApplication()

  @Test
  fun loginForwardsInputsAndSubmitAndSignupOffersConfirmation() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    var submissions = 0
    compose.setContent {
      MaterialTheme {
        AuthScreen(
            state,
            onEmailChange = { state = state.copy(email = it) },
            onPseudoChange = { state = state.copy(pseudo = it) },
            onPasswordChange = { state = state.copy(password = it) },
            onConfirmPasswordChange = { state = state.copy(confirmPassword = it) },
            onSubmit = { submissions++ },
            onSwitchMode = { state = state.copy(mode = AuthMode.SIGN_UP) })
      }
    }
    compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextInput("student@example.org")
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performImeAction()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("student@example.org", state.email)
      assertEquals("secret", state.password)
      assertEquals(2, submissions)
    }
    compose.onNodeWithTag(AuthTestTags.SWITCH_MODE).performScrollTo().performClick()
    compose.onNodeWithTag(AUTH_PSEUDO_TEST_TAG).performScrollTo().performTextInput("Student")
    compose
        .onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD)
        .performScrollTo()
        .performTextInput("secret")
    compose.runOnIdle {
      assertEquals("Student", state.pseudo)
      assertEquals("secret", state.confirmPassword)
    }
  }

  @Test
  fun pendingPseudoFlowShowsOnlyEditableNameAndRetryAction() {
    val state =
        AuthUiState(
            isRestoringSession = false,
            session = com.android.sharemate.model.auth.AuthSession("user", "user@example.org"),
            pseudo = "Taken",
            pendingProfileUpdate = PendingProfileUpdate("user", "Taken"),
            error = AuthUiError.PSEUDO_TAKEN)
    compose.setContent { AuthScreen(state, {}, {}, {}, {}, {}, {}) }

    compose.onNodeWithTag(AUTH_PSEUDO_TEST_TAG).assertIsEnabled()
    compose.onNodeWithTag(AuthTestTags.EMAIL).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.PASSWORD).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).assertDoesNotExist()
    compose
        .onNodeWithTag(AuthTestTags.SUBMIT)
        .assertTextEquals(testApplication.getString(R.string.auth_save_pseudo))
    compose
        .onNodeWithTag(AuthTestTags.ERROR)
        .assertTextEquals(testApplication.getString(R.string.auth_error_pseudo_taken))
  }

  @Test
  fun switchingModeMasksPasswordAfterItWasShown() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    compose.setContent {
      MaterialTheme {
        AuthScreen(
            state,
            onEmailChange = {},
            onPseudoChange = {},
            onPasswordChange = { state = state.copy(password = it) },
            onConfirmPasswordChange = {},
            onSubmit = {},
            onSwitchMode = { state = state.copy(mode = AuthMode.SIGN_UP, password = "") })
      }
    }
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("login-secret")
    compose.onNodeWithText(testApplication.getString(R.string.auth_show_password)).performClick()
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    compose.onNodeWithText(testApplication.getString(R.string.auth_hide_password)).performClick()
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    compose.onNodeWithText(testApplication.getString(R.string.auth_show_password)).performClick()
    compose.onNodeWithTag(AuthTestTags.SWITCH_MODE).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performScrollTo().performTextInput("signup-secret")
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
  }

  @Test
  fun loadingDisablesActionsAndShowsFeedback() {
    compose.setContent {
      AuthScreen(
          AuthUiState(mode = AuthMode.SIGN_UP, isLoading = true, isRestoringSession = false),
          {},
          {},
          {},
          {},
          {},
          {},
          onBack = {})
    }
    compose.onNodeWithTag(AuthTestTags.LOADING).assertExists()
    compose.onNodeWithTag(AuthTestTags.BACK).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.SWITCH_MODE).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.EMAIL).assertIsNotEnabled()
    compose.onNodeWithTag(AUTH_PSEUDO_TEST_TAG).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.PASSWORD).assertIsNotEnabled()
    compose.onNodeWithTag(AuthTestTags.CONFIRM_PASSWORD).assertIsNotEnabled()
  }

  @Test
  fun restoringSessionShowsProgressAndDisablesSubmit() {
    var state by mutableStateOf(AuthUiState())
    compose.setContent { AuthScreen(state, {}, {}, {}, {}, {}, {}) }
    compose.onNodeWithTag(AuthTestTags.LOADING).assertExists()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertIsNotEnabled()
    compose.runOnIdle { state = state.copy(isLoading = true) }
    compose.onNodeWithTag(AuthTestTags.LOADING).assertExists()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertIsNotEnabled()
    compose.runOnIdle { state = state.copy(isRestoringSession = false, isLoading = false) }
    compose.onNodeWithTag(AuthTestTags.LOADING).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertIsEnabled()
  }

  @Test
  fun validationAndProviderFailuresDisplaySafeActionableFeedback() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    compose.setContent { AuthScreen(state, {}, {}, {}, {}, {}, {}) }
    val feedback =
        mapOf(
            AuthUiError.INVALID_EMAIL to R.string.auth_error_email,
            AuthUiError.PSEUDO_REQUIRED to R.string.auth_error_pseudo_required,
            AuthUiError.PSEUDO_TAKEN to R.string.auth_error_pseudo_taken,
            AuthUiError.PSEUDO_TOO_LONG to R.string.auth_error_pseudo_too_long,
            AuthUiError.PASSWORD_REQUIRED to R.string.auth_error_password_required,
            AuthUiError.WEAK_PASSWORD to R.string.auth_error_weak_password,
            AuthUiError.PASSWORD_TOO_LONG to R.string.auth_error_password_too_long,
            AuthUiError.PASSWORD_MISMATCH to R.string.auth_error_password_mismatch,
            AuthUiError.INVALID_CREDENTIALS to R.string.auth_error_credentials,
            AuthUiError.EMAIL_IN_USE to R.string.auth_error_email_in_use,
            AuthUiError.NETWORK to R.string.auth_error_network,
            AuthUiError.TOO_MANY_REQUESTS to R.string.auth_error_rate_limit,
            AuthUiError.PROVIDER_DISABLED to R.string.auth_error_provider_disabled,
            AuthUiError.GOOGLE_UNAVAILABLE to R.string.auth_error_google_unavailable,
            AuthUiError.ACCOUNT_CONFLICT to R.string.auth_error_account_conflict,
            AuthUiError.UNKNOWN to R.string.auth_error_unknown)
    for ((error, messageRes) in feedback) {
      val message =
          when (error) {
            AuthUiError.PSEUDO_TOO_LONG -> testApplication.getString(messageRes, MAX_PSEUDO_LENGTH)
            AuthUiError.PASSWORD_TOO_LONG ->
                testApplication.getString(messageRes, TextLimits.PASSWORD)
            else -> testApplication.getString(messageRes)
          }
      compose.runOnIdle { state = state.copy(error = error) }
      compose.onNodeWithTag(AuthTestTags.ERROR).assertTextEquals(message)
    }
  }
}
