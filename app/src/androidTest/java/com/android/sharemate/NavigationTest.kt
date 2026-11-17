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
              NavigationTestTags.FRIDGE_TAB to R.string.fridge_title,
              NavigationTestTags.RECIPES_TAB to R.string.recipes_title,
              NavigationTestTags.RECEIPTS_TAB to R.string.receipts_title,
              NavigationTestTags.SETTINGS_TAB to R.string.settings_title,
          )

      composeTestRule.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertIsNotEnabled()
      destinations.forEach { (testTag, labelRes) ->
        composeTestRule.onNodeWithTag(testTag).performClick()
        composeTestRule.onNodeWithTag(testTag).assertIsSelected()
        if (testTag == NavigationTestTags.FRIDGE_TAB) {
          composeTestRule
              .onNodeWithTag(C.Tag.fridge_title)
              .assertIsDisplayed()
              .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_title))
          composeTestRule
              .onNodeWithTag(C.Tag.fridge_empty)
              .assertIsDisplayed()
              .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_empty))
        } else {
          composeTestRule
              .onNodeWithTag(NavigationTestTags.PAGE_CONTENT)
              .assertTextEquals(composeTestRule.activity.getString(labelRes))
        }
        if (testTag == NavigationTestTags.RECEIPTS_TAB) {
          composeTestRule
              .onNodeWithText(composeTestRule.activity.getString(R.string.scan_receipt))
              .assertIsDisplayed()
        }
        if (testTag == NavigationTestTags.RECIPES_TAB) {
          composeTestRule
              .onNodeWithText(composeTestRule.activity.getString(R.string.recipes_coming_soon))
              .assertExists()
        } else {
          composeTestRule
              .onNodeWithText(composeTestRule.activity.getString(R.string.recipes_coming_soon))
              .assertDoesNotExist()
        }
      }
    }
  }
}
