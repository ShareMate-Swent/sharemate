// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.MainActivity
import com.android.sharemate.resources.C
import com.android.sharemate.ui.navigation.NavigationTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launcherDisplaysFridgeTitleAndEmptyState() {
    composeTestRule.onNodeWithTag(C.Tag.fridge_screen_container).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed().assertTextEquals("Fridge")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")
  }

  @Test
  fun categoriesAreStaticWithAllSelected() {
    composeTestRule
        .onNodeWithText("All")
        .assertIsDisplayed()
        .assertIsSelected()
        .assertIsNotEnabled()
    listOf("Fruits", "Vegetables", "Canned goods").forEach { category ->
      composeTestRule
          .onNodeWithText(category)
          .performScrollTo()
          .assertIsDisplayed()
          .assertIsNotSelected()
          .assertIsNotEnabled()
    }
  }

  @Test
  fun sortAndOwnershipControlsAreStatic() {
    listOf("Sort", "Yours", "Shared").forEach { label ->
      composeTestRule.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled()
    }
  }

  @Test
  fun bottomBarShowsOnlyFridgeSelectedAndDestinationsEnabled() {
    listOf(
            NavigationTestTags.FRIDGE_TAB to "Fridge",
            NavigationTestTags.RECIPES_TAB to "Recipes",
            NavigationTestTags.RECEIPTS_TAB to "Receipts",
            NavigationTestTags.SETTINGS_TAB to "Settings")
        .forEach { (testTag, label) ->
          val destination = composeTestRule.onNodeWithTag(testTag)
          destination.assertIsDisplayed().assertTextEquals(label).assertIsEnabled()
          if (testTag == NavigationTestTags.FRIDGE_TAB) destination.assertIsSelected()
          else destination.assertIsNotSelected()
        }
  }
}
