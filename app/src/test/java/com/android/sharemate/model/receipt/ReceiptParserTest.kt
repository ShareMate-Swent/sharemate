// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptParserTest {
  @Test
  fun pairsColumnsByHeightAndIgnoresTotals() {
    val lines =
        listOf(
            ReceiptLine("2,40", 90, 1, 11),
            ReceiptLine("3.10", 90, 21, 31),
            ReceiptLine("Lait", 0, 0, 10),
            ReceiptLine("Pain", 0, 20, 30),
            ReceiptLine("TOTAL CHF 5.50", 0, 40, 50))
    assertEquals(
        listOf(ReceiptItem("Lait", 240), ReceiptItem("Pain", 310)), ReceiptParser.parse(lines))
  }

  @Test
  fun ignoresUnreadableAndPaymentOnlyRows() {
    assertEquals(
        emptyList<ReceiptItem>(),
        ReceiptParser.parse(
            listOf(ReceiptLine("???", 0, 0, 10), ReceiptLine("Carte 5.50", 0, 20, 30))))
  }

  @Test
  fun acceptsPriceBelowProductButDoesNotReuseNameForOrphanPrices() {
    val lines =
        listOf(
            ReceiptLine("Pain complet", 0, 0, 10),
            ReceiptLine("CHF 3.10", 90, 12, 22),
            ReceiptLine("1.50", 90, 24, 34),
            ReceiptLine("TOTAL", 0, 36, 46),
            ReceiptLine("4.60", 90, 48, 58))
    assertEquals(listOf(ReceiptItem("Pain complet", 310)), ReceiptParser.parse(lines))
  }
}
