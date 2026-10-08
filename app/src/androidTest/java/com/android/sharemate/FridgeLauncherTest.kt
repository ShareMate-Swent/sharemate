// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.resources.C
import com.android.sharemate.screen.MainScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class FridgeLauncherTest : TestCase() {

  @get:Rule(order = 0) val authenticatedUser = AuthenticatedUserRule()

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun test() = run {
    step("Start Main Activity") {
      composeTestRule.waitUntil(10_000) {
        composeTestRule.onAllNodesWithTag(C.Tag.fridge_title).fetchSemanticsNodes().isNotEmpty()
      }
      ComposeScreen.onComposeScreen<MainScreen>(composeTestRule) {
        fridgeTitle {
          assertIsDisplayed()
          assertTextEquals("Fridge")
        }
        emptyState {
          assertIsDisplayed()
          assertTextEquals("Your fridge is empty")
        }
      }
    }
  }
}
