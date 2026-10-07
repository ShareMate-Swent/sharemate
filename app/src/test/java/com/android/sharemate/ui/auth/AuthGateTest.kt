package com.android.sharemate.ui.auth

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
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
      AuthGate(viewModel) { Text("Private content", Modifier.testTag("private_content")) }
    }
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.onNodeWithTag("auth_restoring").assertIsDisplayed()
    compose.onNodeWithTag("auth_submit").assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(0xFFFEF7FF.toInt(), window.statusBarColor)
      assertEquals(0xFFFEF7FF.toInt(), window.navigationBarColor)
      assertTrue(controller.isAppearanceLightStatusBars)
      assertTrue(controller.isAppearanceLightNavigationBars)
      repository.session.tryEmit(null)
    }
    compose.onNodeWithTag("auth_submit").assertExists()
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.runOnIdle { repository.session.tryEmit(AuthSession("user", "student@example.org")) }
    compose.onNodeWithTag("private_content").assertIsDisplayed()
    compose.onNodeWithTag("auth_submit").assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(Color.BLACK, window.statusBarColor)
      assertEquals(Color.BLACK, window.navigationBarColor)
      assertFalse(controller.isAppearanceLightStatusBars)
      assertFalse(controller.isAppearanceLightNavigationBars)
      viewModel.signOut()
    }
    compose.onNodeWithTag("private_content").assertDoesNotExist()
    compose.onNodeWithTag("auth_submit").assertExists()
  }

  private class UiRepository : AuthRepository {
    override val session = MutableSharedFlow<AuthSession?>(replay = 1)

    override suspend fun signIn(email: String, password: String) {}

    override suspend fun signUp(email: String, password: String) {}

    override fun signOut() {
      session.tryEmit(null)
    }
  }
}
