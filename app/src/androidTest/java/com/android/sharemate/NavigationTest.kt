package com.android.sharemate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

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

      destinations.forEach { (testTag, label) ->
        composeTestRule.onNodeWithTag(testTag).performClick()
        composeTestRule.onNodeWithTag(testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(NavigationTestTags.PAGE_CONTENT).assertTextEquals(label)
      }
    }
  }
}
