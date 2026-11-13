// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemQuantityTest {
  @Test
  fun limitsAreTheDocumentedOnes() {
    assertEquals(1, ItemQuantity.MIN)
    assertEquals(9_999, ItemQuantity.MAX)
    assertEquals(1..9_999, ItemQuantity.RANGE)
  }

  @Test
  fun parseAcceptsTheBoundsAndIgnoresSurroundingSpaces() {
    assertEquals(ItemQuantity.MIN, ItemQuantity.parse("1"))
    assertEquals(ItemQuantity.MAX, ItemQuantity.parse("9999"))
    assertEquals(12, ItemQuantity.parse(" 12 "))
    assertEquals(5, ItemQuantity.parse("005"))
  }

  @Test
  fun parseRefusesWhatIsOutsideTheBoundsOrNotAWholeNumber() {
    for (input in
        listOf(
            "",
            " ",
            "0",
            "-1",
            "10000",
            "99999",
            "2147483647",
            "2147483648",
            "abc",
            "1.5",
            "1e3")) {
      assertNull("Refuse '$input'", ItemQuantity.parse(input))
    }
  }

  @Test
  fun parseRefusesTheDigitsOfPiWithoutOverflowingIntoANegativeNumber() {
    val pi = "3141592653589793238462643383279502884197169399375105820974944592307816406286"
    assertNull(ItemQuantity.parse(pi))
    assertNull(ItemQuantity.parse(pi.take(10)))
    assertNull(ItemQuantity.parse("4294967297"))
  }

  @Test
  fun everyValidQuantityFitsTheInputLength() {
    assertTrue(ItemQuantity.MAX.toString().length <= ItemQuantity.MAX_INPUT_LENGTH)
  }
}
