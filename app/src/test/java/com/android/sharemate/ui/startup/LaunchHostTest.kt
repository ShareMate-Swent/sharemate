// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
  fun brandColorsFollowThemeChanges() {
    val dark = mutableStateOf(false)
    var name = androidx.compose.ui.graphics.Color.Unspecified
    var label = androidx.compose.ui.graphics.Color.Unspecified
    var expectedName = androidx.compose.ui.graphics.Color.Unspecified
    var expectedLabel = androidx.compose.ui.graphics.Color.Unspecified
    compose.setContent {
      com.android.sharemate.ui.theme.ShareMateTheme(darkTheme = dark.value) {
        name = LaunchLayout.nameDark
        label = LaunchLayout.progressLabel
        expectedName = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        expectedLabel = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
      }
    }
    compose.runOnIdle {
      assertEquals(com.android.sharemate.ui.theme.ShareMateBrandColors.darkName, name)
      dark.value = true
    }
    compose.runOnIdle {
      assertEquals(expectedName, name)
      assertEquals(expectedLabel, label)
    }
  }

  @Test
  @Config(sdk = [31], qualifiers = "night")
  fun nativeSplashUsesDarkBackgroundAndLightSystemIcons() {
    val attributes =
        compose.activity.obtainStyledAttributes(
            R.style.Theme_ShareMate_Launch,
            intArrayOf(
                android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar))
    try {
      assertEquals(
          compose.activity.getColor(R.color.launch_dark_background), attributes.getColor(0, 0))
      org.junit.Assert.assertFalse(attributes.getBoolean(1, true))
      org.junit.Assert.assertFalse(attributes.getBoolean(2, true))
    } finally {
      attributes.recycle()
    }
  }

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
  fun shortWindowKeepsRetryReachable() {
    var destinationClicks = 0
    compose.mainClock.autoAdvance = false
    compose.setContent {
      Box(Modifier.requiredSize(320.dp, 240.dp)) {
        LaunchHost {
          val attempt = LocalLaunchAttempt.current
          ReportLaunch(
              LaunchStage.AUTH, if (attempt == 0) LaunchStatus.ERROR else LaunchStatus.READY)
          Box(Modifier.fillMaxSize().clickable { destinationClicks++ }) { Text("Recovered") }
        }
      }
    }
    compose.mainClock.advanceTimeBy(6000)
    compose.onNodeWithTag("launch_reveal").performTouchInput { click(center) }
    repeat(3) {
      compose.onNodeWithTag("launch_reveal").performTouchInput {
        down(Offset(centerX, height * 0.9f))
        // Begin below touch slop, as a finger naturally does before dragging.
        repeat(8) { moveBy(Offset(0f, -1f), delayMillis = 16) }
        moveTo(Offset(centerX, height * 0.1f), delayMillis = 500)
        up()
      }
      compose.mainClock.advanceTimeBy(1000)
    }
    compose.onNodeWithText("Try again").assertIsDisplayed().performTouchInput { click() }
    compose.mainClock.advanceTimeBy(6000)
    compose.onNodeWithTag("launch_reveal").assertDoesNotExist()
    compose.onNodeWithText("Recovered").assertIsDisplayed()
    assertEquals(0, destinationClicks)
    compose.onNodeWithText("Recovered").performTouchInput { click() }
    assertEquals(1, destinationClicks)
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
