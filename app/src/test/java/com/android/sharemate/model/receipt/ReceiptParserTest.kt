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
  fun removesObservedOcrPromotionMarkerOnlyAsACompleteLeadingToken() {
    assertEquals(
        listOf(
            ReceiptItem("Asiago", 315),
            ReceiptItem("iKTual", 240),
            ReceiptItem("Produit iKT", 190)),
        parseRows("iKT Asiago 3.15 1", "iKTual 2.40 1", "Produit iKT 1.90 1"))
  }

  @Test
  fun keepsInlineQuantityProductsAndUsesPriorLabelForQuantityOnlyRows() {
    assertEquals(
        listOf(
            ReceiptItem("Lait", 240), ReceiptItem("Croissant", 240, 2), ReceiptItem("Pain", 310)),
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

  @Test
  fun explicitCountsKeepPrintedRowTotalsAndRemoveUnitPricesFromNames() {
    for (row in
        listOf(
            "2x Lait 4.80",
            "Lait 2 pcs 4.80",
            "Lait 2 × 2.40 4.80",
            "Lait 2 * 2,40 4,80",
            "2x Lait @2.40 4.80")) {
      assertEquals(row, listOf(ReceiptItem("Lait", 480, 2)), parseRows(row))
    }
    assertEquals(listOf(ReceiptItem("Lait", 475, 2)), parseRows("Lait 2 x 2.40 4.75"))
    assertEquals(listOf(ReceiptItem("Lait", 240, 2)), parseRows("2x Lait 2.40"))
  }

  @Test
  fun explicitUnitPricesAreMultipliedOnlyWhenNoTotalWasPrinted() {
    for (row in
        listOf("Lait 2 x 2.40", "2x Lait @2.40", "Lait 2 pcs 2.40/pc", "Lait 2 pcs @2.40 each")) {
      assertEquals(row, listOf(ReceiptItem("Lait", 480, 2)), parseRows(row))
    }
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), parseRows("Lait", "2 x 2.40 each"))
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), parseRows("2x Lait", "@2.40"))
    assertEquals(listOf(ReceiptItem("Lait", null)), parseRows("Lait", "@2.40"))
  }

  @Test
  fun adjacentDetailsAugmentOneProductWithoutDuplicatingItOrReplacingItsTotal() {
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), parseRows("Lait", "2 x 2.40 4.80"))
    assertEquals(listOf(ReceiptItem("Lait", 475, 2)), parseRows("Lait 4.75", "2 x 2.40"))
    assertEquals(listOf(ReceiptItem("Lait", null, 2)), parseRows("Lait 4.75", "2 x 2.40 4.80"))
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), parseRows("Lait 4.80", "2 pcs"))
    assertEquals(
        listOf(ReceiptItem("Lait", 240), ReceiptItem("Lait", 480, 2)),
        parseRows("Lait 2.40", "Lait 4.80", "2 x 2.40"))
  }

  @Test
  fun detailsCannotJumpAcrossHeadersOtherColumnsOrLargeVerticalGaps() {
    assertEquals(listOf(ReceiptItem("Lait", 240)), parseRows("Lait 2.40", "TVA 2.6%", "2 x 2.40"))
    assertEquals(emptyList<ReceiptItem>(), parseRows("Lait", "Caisse 12", "2 x 2.40"))
    for (detail in
        listOf(ReceiptLine("2 x 2.40", 200, 20, 30), ReceiptLine("2 x 2.40", 0, 100, 110))) {
      assertEquals(
          listOf(ReceiptItem("Lait", 240)),
          ReceiptParser.parse(listOf(ReceiptLine("Lait 2.40", 0, 0, 10), detail)))
    }
    assertEquals(emptyList<ReceiptItem>(), parseRows("2 x 2.40"))
  }

  @Test
  fun retainsWeightInformationWithoutInventingAnIntegerCountOrUnitTotal() {
    assertEquals(listOf(ReceiptItem("Pommes 0.350 kg", 140)), parseRows("Pommes 0.350 kg 1.40"))
    assertEquals(listOf(ReceiptItem("Pommes 0.350 kg", 140)), parseRows("Pommes", "0.350 kg 1.40"))
    assertEquals(
        listOf(ReceiptItem("Pommes 0.350 kg", null)), parseRows("Pommes", "0.350 kg @4.00/kg"))
    assertEquals(
        listOf(ReceiptItem("Pommes 0.350 kg", 140)), parseRows("Pommes 1.40", "0.350 kg @4.00/kg"))
    assertEquals(listOf(ReceiptItem("0.5 x Lait", null)), parseRows("Lait", "0.5 x 2.40"))
  }

  @Test
  fun ambiguousPricesAndInvalidCountsRemainReviewableWithoutCrashingTheReceipt() {
    assertEquals(listOf(ReceiptItem("Lait", null)), parseRows("Lait 2.40 4.80"))
    assertEquals(listOf(ReceiptItem("Lait", null)), parseRows("Lait 12345678.90"))
    for (quantity in listOf("0", "-2", "2147483648", "0.50")) {
      assertEquals(
          listOf(ReceiptItem("${quantity}x Lait", null)), parseRows("${quantity}x Lait @2.40"))
    }
    assertEquals(
        listOf(ReceiptItem("Lait", 2_147_483_647L * 999_999_999L, Int.MAX_VALUE)),
        parseRows("Lait 2147483647 x 9999999.99"))
    assertEquals(
        listOf(ReceiptItem("6-pack Eau 1.5 litres", 310)), parseRows("6-pack Eau 1.5 litres 3.10"))
  }

  @Test
  fun alignsQuantityNameAndPriceFragmentsBeforeInterpretingTheirMeaning() {
    assertEquals(
        listOf(ReceiptItem("Lait", 480, 2)),
        ReceiptParser.parse(
            listOf(
                ReceiptLine("4.80", 200, 1, 11),
                ReceiptLine("Lait", 25, 0, 10),
                ReceiptLine("2x", 0, 1, 11))))
  }

  @Test
  fun measurementDecimalsRemainInTheProductRatherThanBecomingMoney() {
    for (weight in listOf("0.35 kg", "0,35 kg", "350 g")) {
      assertEquals(listOf(ReceiptItem("Pommes $weight", 140)), parseRows("Pommes $weight 1.40"))
      assertEquals(
          listOf(ReceiptItem("Pommes $weight", null)), parseRows("Pommes", "$weight @4.00/kg"))
    }
    assertEquals(listOf(ReceiptItem("Eau 1.50 litres", 240)), parseRows("Eau 1.50 litres 2.40"))
  }

  @Test
  fun weightedDetailsCannotAttachToAProductInAnotherColumn() {
    assertEquals(
        emptyList<ReceiptItem>(),
        ReceiptParser.parse(
            listOf(ReceiptLine("Lait", 0, 0, 10), ReceiptLine("0.350 kg 1.40", 200, 20, 30))))
  }

  @Test
  fun detailAssociationRespectsTheSupportedGapAndIndentBoundaries() {
    val product = ReceiptLine("Lait 4.80", 0, 0, 10)
    for (detail in listOf(ReceiptLine("2 pcs", 40, 30, 40), ReceiptLine("2 pcs", 0, 20, 30))) {
      assertEquals(
          listOf(ReceiptItem("Lait", 480, 2)), ReceiptParser.parse(listOf(product, detail)))
    }
    for (detail in listOf(ReceiptLine("2 pcs", 41, 30, 40), ReceiptLine("2 pcs", 40, 31, 41))) {
      assertEquals(listOf(ReceiptItem("Lait", 480)), ReceiptParser.parse(listOf(product, detail)))
    }
  }

  @Test
  fun skuOrPromotionPrefixesCannotTurnSummariesAndHeadersIntoProducts() {
    assertEquals(
        listOf(ReceiptItem("Pain", 310)),
        parseRows(
            "1234567890123 Carte 5.50", "SPZ TVA 2.60", "AKT Caisse 12", "2 x 2.40", "Pain 3.10"))
  }

  @Test
  fun zeroPricedProductsAndMultipleUnitPriceAmbiguityArePreservedForReview() {
    assertEquals(emptyList<ReceiptItem>(), ReceiptParser.parse(emptyList()))
    assertEquals(listOf(ReceiptItem("Gift", 0, 2)), parseRows("Gift 2 pcs @0.00"))
    assertEquals(listOf(ReceiptItem("Lait", null, 2)), parseRows("Lait 2 pcs @2.40 @3.00"))
  }

  @Test
  fun percentageInAProductNameIsNotMistakenForAnotherPrice() {
    assertEquals(listOf(ReceiptItem("Lait 3.50 %", 240)), parseRows("Lait 3.50 % 2.40"))
  }

  @Test
  fun fractionalCountWithTwoDecimalsRemainsUnknownInsteadOfBecomingMoney() {
    val expected = listOf(ReceiptItem("0.50 x Lait", null))
    assertEquals(expected, parseRows("Lait", "0.50 x 2.40"))
    assertEquals(expected, parseRows("Lait 0.50 x 2.40"))
  }

  @Test
  fun laterCountDetailsUseTheExplicitUnitPriceInsteadOfKeepingAStaleDerivedTotal() {
    assertEquals(listOf(ReceiptItem("Lait", 720, 3)), parseRows("Lait 2 pcs @2.40", "3 pcs"))
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), parseRows("Lait @2.40", "2 pcs"))
    assertEquals(listOf(ReceiptItem("Lait", 475, 3)), parseRows("Lait 4.75", "3 pcs"))
  }

  @Test
  fun packageMultipliersRemainInTheNameInsteadOfBecomingPurchasedItemCounts() {
    for ((name, row) in
        listOf(
            "Chocolat 2 x 100g" to "Chocolat 2 x 100g 3.00",
            "Eau 2x 1.50 litres" to "Eau 2x 1.50 litres 3.00")) {
      assertEquals(listOf(ReceiptItem(name, 300)), parseRows(row))
    }
  }

  @Test
  fun invalidOrAmbiguousDetailPricesCannotFallBackToAnEarlierUnitPrice() {
    for (detail in listOf("2 pcs @12345678.90", "2 pcs @2.40 @3.10")) {
      assertEquals(listOf(ReceiptItem("Milk", null, 2)), parseRows("Milk @2.40", detail))
    }
  }
}
