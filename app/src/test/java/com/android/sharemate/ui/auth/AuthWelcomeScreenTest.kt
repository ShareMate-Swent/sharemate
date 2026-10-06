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
      AuthGate(vm) { Text("Private content") }
    }
    compose.onNodeWithTag("auth_welcome").assertExists()
    compose.onNodeWithText("Google", substring = true).assertDoesNotExist()
    compose.onNodeWithTag("auth_welcome_signup").performScrollTo().performClick()
    compose.onNodeWithTag("auth_confirm_password").assertExists()
    compose.onNodeWithTag("auth_email").performTextInput("student@example.org")
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput("different")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag("auth_error").assertTextEquals("The passwords do not match.")
    compose.onNodeWithTag("auth_back").performScrollTo().performClick()
    compose.onNodeWithTag("auth_welcome").assertExists()
    compose.runOnIdle {
      assertEquals("", vm.state.value.password)
      assertEquals("", vm.state.value.confirmPassword)
      assertNull(vm.state.value.error)
      assertTrue(repository.calls.isEmpty())
    }
    compose.onNodeWithTag("auth_welcome_login").performScrollTo().performClick()
    compose.onNodeWithTag("auth_confirm_password").assertDoesNotExist()
    compose
        .onNodeWithTag("auth_email")
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("student@example.org")))
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag("auth_back").assertIsNotEnabled()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag("auth_welcome").assertDoesNotExist()
    compose.onNodeWithTag("auth_loading").assertExists()
    compose.runOnIdle {
      assertEquals(listOf("student@example.org" to "secret"), repository.calls)
      repository.pending.complete(Unit)
    }
    compose.onNodeWithTag("auth_back").assertIsEnabled().performScrollTo().performClick()
    compose.onNodeWithTag("auth_welcome").assertExists()
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
    compose.onNodeWithTag("auth_welcome_login").performScrollTo().assertIsDisplayed().performClick()
    compose
        .onNodeWithTag("auth_welcome_signup")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, loginClicks)
      assertEquals(1, signupClicks)
    }
  }

  private class WelcomeRepository : AuthRepository {
    override val session = MutableStateFlow<AuthSession?>(null)
    val calls = mutableListOf<Pair<String, String>>()
    val pending = CompletableDeferred<Unit>()

    override suspend fun signIn(email: String, password: String) {
      calls.add(email to password)
      pending.await()
    }

    override suspend fun signUp(email: String, password: String) =
        error("Invalid signup reached repository")

    override fun signOut() {
      session.value = null
    }
  }
}
