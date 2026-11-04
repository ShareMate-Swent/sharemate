// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptParserTest {
  private fun parseRows(vararg rows: String) =
      ReceiptParser.parse(
          rows.mapIndexed { index, row -> ReceiptLine(row, 0, index * 20, index * 20 + 10) })

  @Test
  fun stripsLeadingSkuWithoutRemovingProductNumbers() {
    assertEquals(
        listOf(ReceiptItem("Lait 2 litres", 240)), parseRows("1234567890123 Lait 2 litres 2.40"))
  }

  @Test
  fun readsMigrosTaxGroupsAndPromotionMarkersUntilTotal() {
    assertEquals(
        listOf(
            ReceiptItem("Knoblauch", 90),
            ReceiptItem("MClass Landjäger ger.", 405),
            ReceiptItem("Asiago", 315)),
        parseRows(
            "Knoblauch 0.90 1",
            "SPZ MClass Landjäger ger. 4.05 1",
            "AKT Asiago 3.15 2",
            "TOTAL 8.10",
            "M CARD 8.10"))
  }

  @Test
  fun keepsInlineQuantityProductsAndUsesPriorLabelForQuantityOnlyRows() {
    assertEquals(
        listOf(ReceiptItem("Lait", 240), ReceiptItem("Croissant", 240), ReceiptItem("Pain", 310)),
        parseRows(
            "TOTALENERGIES Ecublens",
            "Lait",
            "2.40 A",
            "2x Croissant 2.40",
            "Pain",
            "1 x 3.10 3.10"))
  }

  @Test
  fun orphanPricesDoNotTurnReceiptHeadersIntoProducts() {
    assertEquals(
        emptyList<ReceiptItem>(),
        parseRows(
            "Migros Lausanne", "1 x 3.10 3.10", "Cashier 12", "2.40", "Date 04.11.2026", "1.10"))
  }

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
