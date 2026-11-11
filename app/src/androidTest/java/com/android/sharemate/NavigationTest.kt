// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.resources.C
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {

  @get:Rule(order = 0) val notificationPermission = notificationPermissionRule()

  @get:Rule(order = 0) val authenticatedUser = AuthenticatedUserRule()

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun waitForMembershipAndNavigation() {
    composeTestRule.waitUntil(10_000) {
      composeTestRule
          .onAllNodes(hasTestTag(NavigationTestTags.FRIDGE_TAB))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
  }

  @Test
  fun selectingEachDestinationShowsItsPageLabel() = run {
    step("Select each bottom-bar destination") {
      val destinations =
          listOf(
              NavigationTestTags.FRIDGE_TAB to "Fridge",
              NavigationTestTags.RECIPES_TAB to "Recipes",
              NavigationTestTags.RECEIPTS_TAB to "Receipts",
              NavigationTestTags.SETTINGS_TAB to "Settings",
          )

      composeTestRule.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertIsNotEnabled()
      destinations.forEach { (testTag, label) ->
        composeTestRule.onNodeWithTag(testTag).performClick()
        composeTestRule.onNodeWithTag(testTag).assertIsSelected()
        if (testTag == NavigationTestTags.FRIDGE_TAB) {
          composeTestRule
              .onNodeWithTag(C.Tag.fridge_title)
              .assertIsDisplayed()
              .assertTextEquals("Fridge")
          composeTestRule
              .onNodeWithTag(C.Tag.fridge_empty)
              .assertIsDisplayed()
              .assertTextEquals("Your fridge is empty")
        } else {
          composeTestRule.onNodeWithTag(NavigationTestTags.PAGE_CONTENT).assertTextEquals(label)
        }
        if (label == composeTestRule.activity.getString(R.string.receipts_title)) {
          composeTestRule
              .onNodeWithText(composeTestRule.activity.getString(R.string.scan_receipt))
              .assertIsDisplayed()
        }
        if (label == "Recipes") {
          composeTestRule.onNodeWithText("Coming soon").assertExists()
        } else {
          composeTestRule.onNodeWithText("Coming soon").assertDoesNotExist()
        }
      }
    }
  }
}
