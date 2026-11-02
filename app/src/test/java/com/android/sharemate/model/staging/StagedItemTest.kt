// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StagedItemTest {
  @Test
  fun defaultsToOneUnitWithoutPrice() {
    val item = StagedItem(id = "1", name = "Milk")

    assertEquals(1, item.quantity)
    assertNull(item.priceCents)
  }

  @Test
  fun acceptsZeroPrice() {
    assertEquals(0L, StagedItem("1", "Free sample", priceCents = 0).priceCents)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsBlankName() {
    StagedItem("1", "  ")
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsZeroQuantity() {
    StagedItem("1", "Milk", quantity = 0)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsNegativePrice() {
    StagedItem("1", "Milk", priceCents = -1)
  }

  @Test
  fun parsesWholeAndDecimalPrices() {
    assertEquals(PriceInput.Valid(300), Prices.parse("3"))
    assertEquals(PriceInput.Valid(350), Prices.parse("3.5"))
    assertEquals(PriceInput.Valid(350), Prices.parse("3.50"))
    assertEquals(PriceInput.Valid(350), Prices.parse(" 3,50 "))
    assertEquals(PriceInput.Valid(5), Prices.parse("0.05"))
    assertEquals(PriceInput.Valid(999_999_999), Prices.parse("9999999.99"))
  }

  @Test
  fun emptyPriceMeansNoPrice() {
    assertEquals(PriceInput.Empty, Prices.parse(""))
    assertEquals(PriceInput.Empty, Prices.parse("   "))
  }

  @Test
  fun rejectsMalformedPrices() {
    for (input in listOf("-1", "abc", "3.", ".5", "3.505", "1.2.3", "3 50", "12345678", "€3")) {
      assertEquals(input, PriceInput.Invalid, Prices.parse(input))
    }
  }

  @Test
  fun formatsCentsWithTwoDecimals() {
    assertEquals("", Prices.format(null))
    assertEquals("0.05", Prices.format(5))
    assertEquals("3.50", Prices.format(350))
    assertEquals("12.00", Prices.format(1200))
  }

  @Test
  fun formattedPriceParsesBack() {
    for (cents in listOf(0L, 7L, 350L, 999_999_999L)) {
      assertEquals(PriceInput.Valid(cents), Prices.parse(Prices.format(cents)))
    }
  }
}
