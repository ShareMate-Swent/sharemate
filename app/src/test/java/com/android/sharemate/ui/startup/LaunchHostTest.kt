// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.R
import com.android.sharemate.ui.auth.AuthTestTags
import com.android.sharemate.ui.auth.AuthWelcomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LaunchHostTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun slowLoadNeverDismissesUntilTheDestinationIsReady() {
    val ready = mutableStateOf(false)
    var mounts = 0
    compose.mainClock.autoAdvance = false
    compose.setContent {
      LaunchHost {
        DisposableEffect(Unit) {
          mounts++
          onDispose {}
        }
        ReportLaunch(LaunchStage.AUTH, LaunchStatus.CONTINUE)
        ReportLaunch(LaunchStage.HOUSEHOLD, LaunchStatus.CONTINUE)
        ReportLaunch(
            LaunchStage.DESTINATION, if (ready.value) LaunchStatus.READY else LaunchStatus.WAITING)
        Text("Ready fridge")
      }
    }
    compose.mainClock.advanceTimeBy(6000)
    compose.onNodeWithTag("launch_reveal").assertExists()
    compose.onNodeWithText("60%").assertExists()
    compose.runOnUiThread { ready.value = true }
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(1000)
    compose.onNodeWithTag("launch_reveal").assertDoesNotExist()
    compose.onNodeWithText("Ready fridge").assertIsDisplayed()
    assertEquals(1, mounts)
  }

  @Test
  fun fastLoadStillShowsTheRevealBeforeAuth() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      LaunchHost {
        ReportLaunch(LaunchStage.AUTH, LaunchStatus.READY)
        Text("Sign in")
      }
    }
    compose.mainClock.advanceTimeBy(1000)
    compose.onNodeWithTag("launch_reveal").assertExists()
    compose.mainClock.advanceTimeBy(5000)
    compose.onNodeWithTag("launch_reveal").assertDoesNotExist()
    compose.onNodeWithText("Sign in").assertIsDisplayed()
  }

  @Test
  fun welcomeKeepsTheHandwrittenBrandDuringTheTransitionThenEnablesSignIn() {
    var emailClicks = 0
    var mounts = 0
    lateinit var launchState: LaunchState
    compose.mainClock.autoAdvance = false
    compose.setContent {
      ShareMateLaunch {
        DisposableEffect(Unit) {
          mounts++
          onDispose {}
        }
        val state = LocalLaunchState.current
        SideEffect { if (state != null) launchState = state }
        ReportLaunch(LaunchStage.AUTH, LaunchStatus.READY)
        AuthWelcomeScreen(onLogIn = { emailClicks++ }, onCreateAccount = {})
      }
    }
    compose.mainClock.advanceTimeBy(4100)
    val initial = compose.onNodeWithTag("launch_brand").fetchSemanticsNode().boundsInRoot
    val destinationTop = compose.runOnIdle { requireNotNull(launchState.welcomeBrandBounds).top }
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).assertDoesNotExist()
    compose.mainClock.advanceTimeBy(900)
    val moving = compose.onNodeWithTag("launch_brand").fetchSemanticsNode().boundsInRoot
    assertTrue(
        "The artwork moves up: initial=$initial moving=$moving target=$destinationTop",
        moving.top < initial.top)
    assertTrue(
        "The brand has not jumped straight to its final position", moving.top > destinationTop)
    assertEquals(initial.width, moving.width, 0.5f)
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).assertDoesNotExist()
    compose.mainClock.advanceTimeBy(1500)
    compose.onNodeWithTag("launch_reveal").assertDoesNotExist()
    compose.onNodeWithContentDescription("ShareMate").assertIsDisplayed()
    val header = compose.onNodeWithTag(AuthTestTags.BRAND_HEADER).fetchSemanticsNode().boundsInRoot
    assertEquals(destinationTop, header.top, 0.5f)
    assertEquals(initial.width, header.width, 0.5f)
    val name = compose.onNodeWithContentDescription("ShareMate").fetchSemanticsNode().boundsInRoot
    val title =
        compose
            .onNodeWithText(compose.activity.getString(R.string.auth_welcome_title))
            .fetchSemanticsNode()
            .boundsInRoot
    val gap = title.top - name.bottom
    assertTrue(
        "The title follows the brand without overlap or a large empty gap",
        gap >= 0f && gap <= 16f * compose.activity.resources.displayMetrics.density)
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    assertEquals(1, emailClicks)
    assertEquals(1, mounts)
  }

  @Test
  fun failureOffersRetryWithoutShowingAFalseCompletion() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      LaunchHost {
        val attempt = LocalLaunchAttempt.current
        ReportLaunch(LaunchStage.AUTH, if (attempt == 0) LaunchStatus.ERROR else LaunchStatus.READY)
        Text("Sign in")
      }
    }
    compose.mainClock.advanceTimeBy(6000)
    compose.onNodeWithText("Try again").performClick()
    compose.mainClock.advanceTimeBy(6000)
    compose.onNodeWithTag("launch_reveal").assertDoesNotExist()
    compose.onNodeWithText("Sign in").assertIsDisplayed()
  }
}
