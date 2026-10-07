// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.ui.test.assertIsDisplayed
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
  fun bottomBarShowsOnlyFridgeSelectedAndDestinationsDisabled() {
    listOf("Fridge", "Recipes", "Receipts", "Settings").forEachIndexed { index, label ->
      val destination = composeTestRule.onNodeWithTag("${C.Tag.fridge_navigation_prefix}$index")
      destination.assertIsDisplayed().assertTextEquals(label).assertIsNotEnabled()
      if (index == 0) destination.assertIsSelected() else destination.assertIsNotSelected()
    }
  }
}
