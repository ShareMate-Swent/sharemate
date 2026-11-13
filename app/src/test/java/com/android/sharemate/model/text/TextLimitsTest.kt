// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLimitsTest {
  private val emoji = "😀"

  @Test
  fun limitsAreTheDocumentedOnes() {
    assertEquals(40, TextLimits.HOUSEHOLD_NAME)
    assertEquals(80, TextLimits.ITEM_NAME)
    assertEquals(40, TextLimits.ITEM_CATEGORY)
  }

  @Test
  fun codePointLengthCountsAnEmojiOnce() {
    assertEquals(0, "".codePointLength())
    assertEquals(3, "abc".codePointLength())
    assertEquals(2, (emoji + emoji).codePointLength())
    assertEquals(4, (emoji + emoji).length)
  }

  @Test
  fun exceedsIsFalseAtTheLimitAndTrueOneCodePointAbove() {
    assertFalse("".exceeds(0))
    assertTrue("a".exceeds(0))
    for (max in listOf(TextLimits.HOUSEHOLD_NAME, TextLimits.ITEM_NAME, TextLimits.ITEM_CATEGORY)) {
      assertFalse("a".repeat(max - 1).exceeds(max))
      assertFalse("a".repeat(max).exceeds(max))
      assertTrue("a".repeat(max + 1).exceeds(max))
      assertFalse(emoji.repeat(max).exceeds(max))
      assertTrue(emoji.repeat(max + 1).exceeds(max))
    }
  }

  @Test
  fun limitedToKeepsTextWithinTheLimitUntouched() {
    val text = "a".repeat(TextLimits.ITEM_NAME)
    assertSame(text, text.limitedTo(TextLimits.ITEM_NAME))
    assertEquals("", "".limitedTo(TextLimits.ITEM_NAME))
  }

  @Test
  fun limitedToCutsOnTheLimit() {
    assertEquals(
        "a".repeat(TextLimits.ITEM_NAME), "a".repeat(10_000).limitedTo(TextLimits.ITEM_NAME))
    assertEquals("", "abc".limitedTo(0))
  }

  @Test
  fun limitedToNeverSplitsASurrogatePair() {
    val cut = (emoji.repeat(TextLimits.ITEM_NAME + 5)).limitedTo(TextLimits.ITEM_NAME)
    assertEquals(emoji.repeat(TextLimits.ITEM_NAME), cut)
    assertEquals("a$emoji", "a${emoji}b".limitedTo(2))
  }
}
