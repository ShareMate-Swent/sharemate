// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HouseholdChoiceScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun displaysOptionsAndInvokesCallbacks() {
    var creates = 0
    var joins = 0
    composeTestRule.setContent {
      MaterialTheme {
        HouseholdChoiceScreen(onCreateHousehold = { creates++ }, onJoinHousehold = { joins++ })
      }
    }
    composeTestRule.onNodeWithText("Create a household").assertIsDisplayed()
    composeTestRule.onNodeWithText("Join a household").assertIsDisplayed()
    composeTestRule.onNodeWithTag(HouseholdChoiceTestTags.CREATE).performClick()
    composeTestRule.onNodeWithTag(HouseholdChoiceTestTags.JOIN).performClick()
    assertEquals(1, creates)
    assertEquals(1, joins)
  }
}
