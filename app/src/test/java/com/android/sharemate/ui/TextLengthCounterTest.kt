// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.android.sharemate.R
import com.android.sharemate.model.text.TextLimits
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TextLengthCounterTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private fun counter(used: Int, max: Int) =
      composeTestRule.activity.getString(R.string.text_length_counter, used, max)

  @Test
  fun showsTheLengthAndTheLimit() {
    composeTestRule.setContent { TextLengthCounter("Milk", TextLimits.ITEM_NAME) }

    composeTestRule.onNodeWithText("4/${TextLimits.ITEM_NAME}").assertExists()
    assertEquals("4/80", counter(4, TextLimits.ITEM_NAME))
  }

  @Test
  fun showsAnEmptyTextAndTheFullLimit() {
    composeTestRule.setContent { TextLengthCounter("", TextLimits.HOUSEHOLD_NAME) }
    composeTestRule.onNodeWithText("0/${TextLimits.HOUSEHOLD_NAME}").assertExists()
  }

  @Test
  fun countsAnEmojiAsOneCharacter() {
    composeTestRule.setContent {
      TextLengthCounter("😀".repeat(TextLimits.ITEM_CATEGORY), TextLimits.ITEM_CATEGORY)
    }
    composeTestRule
        .onNodeWithText("${TextLimits.ITEM_CATEGORY}/${TextLimits.ITEM_CATEGORY}")
        .assertExists()
  }
}
