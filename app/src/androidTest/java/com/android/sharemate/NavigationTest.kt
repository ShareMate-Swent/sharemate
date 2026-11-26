// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.resources.C
import com.android.sharemate.ui.chat.ChatTestTags
import com.android.sharemate.ui.navigation.AddItemMethod
import com.android.sharemate.ui.navigation.AddItemSheetTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.receipts.ReceiptDashboardTestTags
import com.android.sharemate.ui.recipes.RecipesTestTags
import com.android.sharemate.ui.settings.SettingsTestTags
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
              NavigationTestTags.CHAT_TAB to R.string.chat_title,
          )

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
        } else if (testTag == NavigationTestTags.CHAT_TAB) {
          composeTestRule.onNodeWithTag(ChatTestTags.SCREEN).assertIsDisplayed()
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
              .onNodeWithText(composeTestRule.activity.getString(R.string.recipes_idle))
              .assertExists()
        } else {
          composeTestRule
              .onNodeWithText(composeTestRule.activity.getString(R.string.recipes_idle))
              .assertDoesNotExist()
        }
      }
    }
    step("Open Settings from the profile photo and come back") {
      composeTestRule.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
      composeTestRule.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY).performClick()
      composeTestRule
          .onNodeWithTag(NavigationTestTags.PAGE_CONTENT)
          .assertTextEquals(composeTestRule.activity.getString(R.string.settings_title))
      composeTestRule.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
      composeTestRule.onNodeWithTag(SettingsTestTags.BACK).performClick()
      composeTestRule.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsSelected()
    }
  }

  @Test
  fun emptyFridgeRecipeRequestOpensTheExistingDestinationAndKeepsItsResult() = run {
    step("Request suggestions from the fridge") {
      composeTestRule.onNodeWithTag(C.Tag.fridge_suggest_recipes).assertIsDisplayed().performClick()
      composeTestRule.onNodeWithTag(NavigationTestTags.RECIPES_TAB).assertIsSelected()
      composeTestRule.waitUntil(5_000) {
        composeTestRule
            .onAllNodes(hasTestTag(RecipesTestTags.EMPTY))
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      composeTestRule.onNodeWithTag(RecipesTestTags.DEMO_NOTICE).assertDoesNotExist()
      composeTestRule.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    }
    step("Revisit Recipes without another button press") {
      composeTestRule.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
      composeTestRule.onNodeWithTag(C.Tag.fridge_suggest_recipes).assertIsDisplayed()
      composeTestRule.onNodeWithTag(NavigationTestTags.RECIPES_TAB).performClick()
      composeTestRule.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
      composeTestRule.onNodeWithTag(RecipesTestTags.IDLE).assertDoesNotExist()
    }
  }

  @Test
  fun receiptScanFromTheAddButtonOnChatGoesBackToChatWhenCancelled() = run {
    step("Open the receipt camera from the add button on Chat") {
      composeTestRule.onNodeWithTag(NavigationTestTags.CHAT_TAB).performClick()
      composeTestRule.onNodeWithTag(NavigationTestTags.ADD_BUTTON).performClick()
      composeTestRule
          .onNodeWithTag(AddItemSheetTestTags.option(AddItemMethod.RECEIPT))
          .performClick()
      composeTestRule.onNodeWithTag(ReceiptDashboardTestTags.BACK).assertIsDisplayed()
    }
    step("Cancel the photo") {
      composeTestRule.onNodeWithTag(ReceiptDashboardTestTags.BACK).performClick()
      composeTestRule.onNodeWithTag(ReceiptDashboardTestTags.BACK).assertDoesNotExist()
      composeTestRule.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertIsSelected()
      composeTestRule.onNodeWithTag(ChatTestTags.SCREEN).assertIsDisplayed()
    }
  }
}
