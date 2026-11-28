// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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

/** Checks the banner used by the forms and pages that report a state to the user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppBannerTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private fun show(tone: AppBannerTone) {
    composeTestRule.setContent {
      MaterialTheme { AppBanner(message = "Something happened", tone = tone, testTag = "message") }
    }
  }

  @Test
  fun everyToneShowsItsMessageWithTheTag() {
    composeTestRule.setContent {
      MaterialTheme {
        Column {
          AppBannerTone.values().forEach { tone ->
            AppBanner(message = "Message of ${tone.name}", tone = tone, testTag = tone.name)
          }
        }
      }
    }

    AppBannerTone.values().forEach { tone ->
      composeTestRule
          .onNodeWithTag(tone.name)
          .assertIsDisplayed()
          .assertTextEquals("Message of ${tone.name}")
    }
  }

  @Test
  fun exposesPoliteLiveRegionSemantics() {
    show(AppBannerTone.ERROR)

    composeTestRule
        .onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        .assertIsDisplayed()
  }
}
