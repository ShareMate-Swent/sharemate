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
  fun positionedWordsKeepProductNamesAndUseThePrintedTotalAfterDiscount() {
    val lines =
        listOf(
            positionedLine(20, 10 to "Article", 140 to "Quant.", 200 to "Prix"),
            positionedLine(21, 270 to "Rabais", 340 to "Total", 405 to "#"),
            positionedLine(
                40,
                10 to "Eau",
                39 to "6x1.5L",
                149 to "2",
                200 to "4.80",
                269 to "-1.30",
                340 to "8.30",
                405 to "1"),
            positionedLine(60, 10 to "Pain", 149 to "1", 200 to "3,10", 340 to "3,10"),
            positionedLine(80, 10 to "Total", 45 to "CHF", 340 to "11.40"),
            positionedLine(100, 10 to "Visa", 340 to "11.40"))

    assertEquals(
        listOf(ReceiptItem("Eau 6x1.5L", 830, 2), ReceiptItem("Pain", 310)),
        ReceiptParser.parse(lines))
  }

  @Test
  fun curvedReceiptCanHaveADifferentAngleAtTheHeaderAndProductRows() {
    val lines =
        listOf(
            ReceiptLine("Qty", 140, 20, 30),
            ReceiptLine("Price", 200, 18, 28),
            ReceiptLine("Total", 340, 15, 25),
            ReceiptLine("Pain", 10, 60, 70),
            ReceiptLine("3.10", 340, 65, 75),
            ReceiptLine("Lait", 10, 85, 95),
            ReceiptLine("4.80", 340, 90, 100))
    assertEquals(
        listOf(ReceiptItem("Pain", 310), ReceiptItem("Lait", 480)), ReceiptParser.parse(lines))
  }

  @Test
  fun headerWithoutTableItemsDoesNotDiscardPreviouslyParsedProducts() {
    val lines =
        listOf(
            ReceiptLine("Lait 2 x 2.40 4.80", 10, 0, 10),
            positionedLine(20, 140 to "Qty", 200 to "Price", 340 to "Total"))
    assertEquals(listOf(ReceiptItem("Lait", 480, 2)), ReceiptParser.parse(lines))
  }

  @Test
  fun rightAlignedTotalStopsTableBeforeFooterAmounts() {
    val lines =
        listOf(
            positionedLine(20, 140 to "Qty", 200 to "Price", 340 to "Total"),
            positionedLine(40, 10 to "Pain", 149 to "1", 200 to "3.10", 340 to "3.10"),
            positionedLine(60, 200 to "Total", 245 to "CHF", 340 to "3.10"),
            positionedLine(80, 10 to "Paiement", 149 to "1", 200 to "3.10", 340 to "3.10"))
    assertEquals(listOf(ReceiptItem("Pain", 310)), ReceiptParser.parse(lines))
  }

  @Test
  fun splitColumnFragmentsAreMatchedByHeightWithoutBorrowingTotalsFromAnotherRow() {
    val lines =
        listOf(
            ReceiptLine("Total", 340, 21, 31),
            ReceiptLine("Quant.", 140, 20, 30),
            ReceiptLine("Prix", 200, 19, 29),
            ReceiptLine("6.60", 340, 63, 73),
            ReceiptLine("2.20", 200, 61, 71),
            ReceiptLine("3", 149, 61, 71),
            ReceiptLine("Pizzetta", 10, 60, 70),
            ReceiptLine("Valflora", 10, 40, 50),
            ReceiptLine("2", 149, 41, 51),
            ReceiptLine("2.85", 200, 42, 52),
            ReceiptLine("Total CHF 6.60", 10, 82, 92))

    assertEquals(
        listOf(ReceiptItem("Valflora", null, 2), ReceiptItem("Pizzetta", 660, 3)),
        ReceiptParser.parse(lines))
  }

  @Test
  fun malformedTotalsStayReviewableAndNegativeTotalRowsAreSkipped() {
    val lines =
        listOf(
            positionedLine(20, 10 to "Article", 140 to "Quant.", 200 to "Prix", 340 to "Total"),
            positionedLine(40, 10 to "Crème", 149 to "2", 200 to "2.85", 340 to "5.7O"),
            positionedLine(60, 10 to "Retour produit", 149 to "1", 200 to "0.50", 340 to "-0.50"),
            positionedLine(80, 10 to "Échantillon", 149 to "1", 200 to "0.00", 340 to "0.00"))

    assertEquals(
        listOf(ReceiptItem("Crème", null, 2), ReceiptItem("Échantillon", 0)),
        ReceiptParser.parse(lines))
  }

  @Test
  fun preservesWeightsAndOutOfRangeCountsForReviewWithoutGuessingCountsFromPrices() {
    val lines =
        listOf(
            positionedLine(20, 140 to "Quantité", 200 to "Prix", 340 to "Total"),
            positionedLine(40, 10 to "Pommes", 145 to "0.35 kg"),
            positionedLine(60, 10 to "Pain", 149 to "0", 340 to "2.00"),
            positionedLine(80, 10 to "Thé", 149 to "100", 340 to "4.00"),
            positionedLine(100, 10 to "Pizzetta", 200 to "2.20", 340 to "6.60"))

    assertEquals(
        listOf(
            ReceiptItem("0.35 kg Pommes", null),
            ReceiptItem("0 Pain", 200),
            ReceiptItem("100 Thé", 400),
            ReceiptItem("Pizzetta", 660)),
        ReceiptParser.parse(lines))
  }

  @Test
  fun excludesTableMetadataAndKeepsOnlyUnambiguousTotals() {
    val lines =
        listOf(
            positionedLine(20, 140 to "Menge", 200 to "Einzelpreis", 340 to "Betrag"),
            positionedLine(40, 10 to "TVA", 149 to "1", 340 to "0.50"),
            positionedLine(60, 10 to "Caisse", 149 to "2", 340 to "0.50"),
            positionedLine(80, 10 to "En-tête sans chiffres"),
            positionedLine(100, 10 to "Lait", 149 to "2", 340 to "EUR", 370 to "4.80"),
            positionedLine(120, 10 to "Pain", 149 to "1", 340 to "3.10", 375 to "4.10"),
            positionedLine(140, 10 to "Thé", 149 to "1", 340 to "2.00", 375 to "G"))

    assertEquals(
        listOf(ReceiptItem("Lait", null, 2), ReceiptItem("Pain", null), ReceiptItem("Thé", 200)),
        ReceiptParser.parse(lines))
  }

  @Test
  fun incompleteOrMisalignedColumnHeadersKeepTheOrdinaryReceiptFormat() {
    val ordinaryRows =
        listOf(
            ReceiptLine("2x Lait 4.80", 10, 0, 10),
            positionedLine(50, 10 to "2x", 35 to "Pain", 340 to "3.10"))
    val falseHeaders =
        listOf(
            listOf(positionedLine(20, 140 to "Qty", 340 to "Total")),
            listOf(positionedLine(20, 140 to "Qty", 200 to "Price")),
            listOf(
                positionedLine(20, 140 to "Qty", 340 to "Total"),
                positionedLine(29, 200 to "Price")))

    for (falseHeader in falseHeaders) {
      assertEquals(
          listOf(ReceiptItem("Lait", 480, 2), ReceiptItem("Pain", 310, 2)),
          ReceiptParser.parse(ordinaryRows + falseHeader))
    }
  }

  @Test
  fun slopedTableKeepsEachExplicitQuantityAndTotalOnItsOwnRow() {
    val lines =
        listOf(
            positionedLine(47, 10 to "Article"),
            positionedLine(40, 140 to "Quant."),
            positionedLine(36, 210 to "Prix"),
            positionedLine(30, 340 to "Total"),
            positionedLine(62, 10 to "Pizzetta"),
            positionedLine(55, 149 to "3"),
            positionedLine(51, 210 to "2.20"),
            positionedLine(45, 340 to "6.60"),
            positionedLine(87, 10 to "Crème"),
            positionedLine(80, 149 to "2"),
            positionedLine(76, 210 to "2.85"),
            positionedLine(70, 340 to "5.70"),
            positionedLine(107, 10 to "Total", 45 to "CHF"),
            positionedLine(90, 340 to "12.30"))

    assertEquals(
        listOf(ReceiptItem("Pizzetta", 660, 3), ReceiptItem("Crème", 570, 2)),
        ReceiptParser.parse(lines))
  }

  private fun positionedLine(top: Int, vararg cells: Pair<Int, String>): ReceiptLine =
      ReceiptLine(
          cells.joinToString(" ") { it.second },
          cells.first().first,
          top,
          top + 10,
          cells.map { (left, text) ->
            ReceiptWord(text, left, top, left + text.length * 5, top + 10)
          })

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
  fun multiplierSyntaxDoesNotConsumeTheFirstLetterOfProductWords() {
    assertEquals(
        listOf(
            ReceiptItem("Chips 2 XXL", 350),
            ReceiptItem("3 Xylitol gum", 250),
            ReceiptItem("Lait", 480, 2)),
        parseRows("Chips 2 XXL 3.50", "3 Xylitol gum 2.50", "2x Lait 4.80"))
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
  fun negativeDiscountsAreSkippedAndBreakQuantityDetailAssociation() {
    for (discount in listOf("Aktion -0.60", "Aktion CHF −0.60", "Aktion 0.60-")) {
      assertEquals(
          discount,
          listOf(ReceiptItem("Lait", 240), ReceiptItem("Asiago", 315)),
          parseRows("Lait 2.40", discount, "2 x 1.20", "AKT Asiago 3.15 2"))
      assertEquals(discount, emptyList<ReceiptItem>(), parseRows("Lait", discount, "2 x 1.20"))
    }
  }

  @Test
  fun embeddedHyphensAndSignedFractionalCountsAreNotDiscounts() {
    assertEquals(listOf(ReceiptItem("Produit REF-2.40", 350)), parseRows("Produit REF-2.40 3.50"))
    assertEquals(listOf(ReceiptItem("-0.50 x Lait", null)), parseRows("Lait -0.50 x 2.40"))
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
    assertEquals(listOf(ReceiptItem("Lait 2.40", null)), parseRows("Lait 2.40 4.80"))
    assertEquals(listOf(ReceiptItem("Coca-Cola 0.50", null)), parseRows("Coca-Cola 0.50 2.20"))
    assertEquals(listOf(ReceiptItem("Lait", null)), parseRows("Lait 12345678.90"))
    for (quantity in listOf("0", "-2", "2147483648", "0.50")) {
      assertEquals(
          listOf(ReceiptItem("${quantity}x Lait", null)), parseRows("${quantity}x Lait @2.40"))
    }
    assertEquals(
        listOf(ReceiptItem("2147483647 x Lait", null)), parseRows("Lait 2147483647 x 9999999.99"))
    assertEquals(
        listOf(ReceiptItem("6-pack Eau 1.5 litres", 310)), parseRows("6-pack Eau 1.5 litres 3.10"))
  }

  @Test
  fun countsAboveThePlausibilityLimitKeepTheirTextAndAnIndependentPrintedTotal() {
    assertEquals(listOf(ReceiptItem("Lait", 11_760, 98)), parseRows("98x Lait @1.20"))
    assertEquals(listOf(ReceiptItem("Lait", 11_880, 99)), parseRows("99x Lait @1.20"))
    assertEquals(listOf(ReceiptItem("100x Lait", null)), parseRows("100x Lait @1.20"))
    assertEquals(listOf(ReceiptItem("100x Lait", 240)), parseRows("100x Lait @1.20 2.40"))
    assertEquals(
        listOf(ReceiptItem("2147483648x Lait", 240)), parseRows("2147483648x Lait @1.20 2.40"))
  }

  @Test
  fun invalidCountDetailsCannotReuseEarlierCountsOrDerivedTotals() {
    assertEquals(listOf(ReceiptItem("100 x Lait", null)), parseRows("2x Lait", "100 x 1.20"))
    assertEquals(listOf(ReceiptItem("100 pcs Lait", null)), parseRows("2x Lait @1.20", "100 pcs"))
    assertEquals(listOf(ReceiptItem("100 pcs Lait", 240)), parseRows("2x Lait 2.40", "100 pcs"))
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
  fun uppercaseMeasurementUnitsAndTrailingTaxGroupsAreDistinguished() {
    assertEquals(
        listOf(ReceiptItem("Cola 1.50 L", 220), ReceiptItem("Épices 0.50 G", 240)),
        parseRows("Cola 1.50 L 2.20 G", "Épices 0.50 G 2.40 L"))
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
    assertEquals(listOf(ReceiptItem("Eau 6x1.5L", 540)), parseRows("Eau 6x1.5L 5.40"))
  }

  @Test
  fun invalidOrAmbiguousDetailPricesCannotFallBackToAnEarlierUnitPrice() {
    for (detail in listOf("2 pcs @12345678.90", "2 pcs @2.40 @3.10")) {
      assertEquals(listOf(ReceiptItem("Milk", null, 2)), parseRows("Milk @2.40", detail))
    }
  }
}
