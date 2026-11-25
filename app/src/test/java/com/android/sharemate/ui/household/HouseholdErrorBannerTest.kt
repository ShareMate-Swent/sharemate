// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the error banner shown by the household forms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HouseholdErrorBannerTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun showsTheMessageWithItsTag() {
    composeTestRule.setContent {
      MaterialTheme { HouseholdErrorBanner(message = "It failed", testTag = "banner_message") }
    }
    composeTestRule
        .onNodeWithTag("banner_message")
        .assertIsDisplayed()
        .assertTextEquals("It failed")
  }

  @Test
  fun isAPoliteLiveRegionSoScreenReadersAnnounceIt() {
    composeTestRule.setContent {
      MaterialTheme { HouseholdErrorBanner(message = "It failed", testTag = "banner_message") }
    }
    composeTestRule
        .onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        .assertIsDisplayed()
  }
}
