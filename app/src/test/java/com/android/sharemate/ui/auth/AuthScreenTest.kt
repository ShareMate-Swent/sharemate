package com.android.sharemate.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun loginForwardsInputsAndSubmitAndSignupOffersConfirmation() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    var submissions = 0
    compose.setContent {
      MaterialTheme {
        AuthScreen(
            state,
            onEmailChange = { state = state.copy(email = it) },
            onPasswordChange = { state = state.copy(password = it) },
            onConfirmPasswordChange = { state = state.copy(confirmPassword = it) },
            onSubmit = { submissions++ },
            onSwitchMode = { state = state.copy(mode = AuthMode.SIGN_UP) })
      }
    }
    compose.onNodeWithTag("auth_confirm_password").assertDoesNotExist()
    compose.onNodeWithTag("auth_email").performTextInput("student@example.org")
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    compose.onNodeWithTag("auth_password").performImeAction()
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("student@example.org", state.email)
      assertEquals("secret", state.password)
      assertEquals(2, submissions)
    }
    compose.onNodeWithTag("auth_switch_mode").performScrollTo().performClick()
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput("secret")
    compose.runOnIdle { assertEquals("secret", state.confirmPassword) }
  }

  @Test
  fun switchingModeMasksPasswordAfterItWasShown() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    compose.setContent {
      MaterialTheme {
        AuthScreen(
            state,
            onEmailChange = {},
            onPasswordChange = { state = state.copy(password = it) },
            onConfirmPasswordChange = {},
            onSubmit = {},
            onSwitchMode = { state = state.copy(mode = AuthMode.SIGN_UP, password = "") })
      }
    }
    compose.onNodeWithTag("auth_password").performTextInput("login-secret")
    compose.onNodeWithText("Show").performClick()
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    compose.onNodeWithText("Hide").performClick()
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    compose.onNodeWithText("Show").performClick()
    compose.onNodeWithTag("auth_switch_mode").performScrollTo().performClick()
    compose.onNodeWithTag("auth_password").performScrollTo().performTextInput("signup-secret")
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
  }

  @Test
  fun loadingDisablesActionsAndShowsFeedback() {
    compose.setContent {
      AuthScreen(AuthUiState(isLoading = true, isRestoringSession = false), {}, {}, {}, {}, {})
    }
    compose.onNodeWithTag("auth_loading").assertExists()
    compose.onNodeWithTag("auth_submit").assertIsNotEnabled()
    compose.onNodeWithTag("auth_switch_mode").assertIsNotEnabled()
    compose.onNodeWithTag("auth_email").assertIsNotEnabled()
    compose.onNodeWithTag("auth_password").assertIsNotEnabled()
  }

  @Test
  fun validationAndProviderFailuresDisplaySafeActionableFeedback() {
    var state by mutableStateOf(AuthUiState(isRestoringSession = false))
    compose.setContent { AuthScreen(state, {}, {}, {}, {}, {}) }
    val feedback =
        mapOf(
            AuthUiError.INVALID_EMAIL to "Enter a valid email address.",
            AuthUiError.PASSWORD_REQUIRED to "Enter your password.",
            AuthUiError.WEAK_PASSWORD to "Choose a stronger password with at least 6 characters.",
            AuthUiError.PASSWORD_MISMATCH to "The passwords do not match.",
            AuthUiError.INVALID_CREDENTIALS to "Unable to log in. Check your email and password.",
            AuthUiError.EMAIL_IN_USE to
                "Unable to create an account with this email. Try logging in instead.",
            AuthUiError.NETWORK to "Check your internet connection and try again.",
            AuthUiError.TOO_MANY_REQUESTS to "Too many attempts. Please try again later.",
            AuthUiError.PROVIDER_DISABLED to
                "Email sign-in is currently unavailable. Please try again later.",
            AuthUiError.UNKNOWN to "Something went wrong. Please try again.")
    for ((error, message) in feedback) {
      compose.runOnIdle { state = state.copy(error = error) }
      compose.onNodeWithTag("auth_error").assertTextEquals(message)
    }
  }
}
