// Co-authored-by: Codex
package com.android.sharemate.model.receipt

/** Vertical bounds allow OCR blocks for names and prices to be read as one printed row. */
internal data class ReceiptLine(val text: String, val left: Int, val top: Int, val bottom: Int)

/** [priceCents] is the entire row total, never a unit price; null means it needs review. */
data class ReceiptItem(val name: String, val priceCents: Long?, val quantity: Int = 1)

internal object ReceiptParser {
  private const val CENTS_PER_UNIT = 100L
  private const val MAX_WHOLE_DIGITS = 7
  private const val MAX_SCANNED_QUANTITY = 99
  private const val MAX_ROW_GAP_HEIGHTS = 2
  private const val MAX_DETAIL_INDENT_HEIGHTS = 4
  // A separate uppercase G/L at the end of a row can be a tax group.
  private const val MEASUREMENT_UNIT =
      """(?:(?i:kg|mg|ml|cl|litres?|liters?)\b|[gl]\b|[GL]\b(?=\s+\S))"""
  private const val COUNT_MARKER = """(?:PCS?\b|[x×*](?!\p{L}))"""
  private val amount =
      Regex(
          """(?<![\w.,+\-])(?:CHF\s*)?(\d+)[.,](\d{2})(?:\s*CHF)?(?![\w.,])""",
          RegexOption.IGNORE_CASE)
  private val total = Regex("""^total\b""", RegexOption.IGNORE_CASE)
  // A sign inside a product reference or a signed count is not a discount.
  private val negativeAmount =
      Regex(
          """(?<!\S)(?:CHF\s*)?(?:[-−]\s*(?:CHF\s*)?\d+[.,]\d{2}(?![\w.,]|\s*$COUNT_MARKER)|\d+[.,]\d{2}(?:\s*CHF)?\s*[-−](?![\w.,]))""",
          RegexOption.IGNORE_CASE)
  private val summary =
      Regex(
          """^(?:(?:sous[- ]?|sub[- ]?)?total|tva|mwst|vat|cash|carte|visa|mastercard|rendu|rabais|remise)\b""",
          RegexOption.IGNORE_CASE)
  private val count =
      Regex("""(?<!\S)([-−]?\d+(?:[.,]\d+)?)\s*$COUNT_MARKER""", RegexOption.IGNORE_CASE)
  private val unitPrefix = Regex("""(?:^|\s)(?:[x×*@]|à)\s*$""", RegexOption.IGNORE_CASE)
  private val unitSuffix = Regex("""^\s*(?:/\s*(?:pcs?|kg|g)\b|each\b)""", RegexOption.IGNORE_CASE)
  private val taxSuffix = Regex("""^\s*[A-Z0-9]?\s*$""", RegexOption.IGNORE_CASE)
  private val weightOnly = Regex("""^\d+(?:[.,]\d+)?\s*(?:kg|g)$""", RegexOption.IGNORE_CASE)
  private val measurementSuffix = Regex("""^\s*(?:$MEASUREMENT_UNIT|%)""")
  private val packageSize = Regex("""^\s*\d+(?:[.,]\d+)?(?:\s*$MEASUREMENT_UNIT|[GL]\b)""")
  private val spaces = Regex("""\s+""")
  private val leadingBarcode = Regex("""^\d[\d+\-]{7,15}\s+""")
  // ML Kit reads the printed AKT promotion marker as iKT on the real Migros fixture.
  // Match only these complete leading tokens; never rewrite text inside a product name.
  private val marker = Regex("""^(?:(?:SPZ|AKT|iKT)\s+)+""")
  private val header =
      Regex(
          """^(?:migros|totalenergies|date|caisse|caissier|kasse|cashier)\b""",
          RegexOption.IGNORE_CASE)

  /**
   * Recognizes explicit x/pcs counts, adjacent quantity details and unit prices marked by x/@/à or
   * /pc. An unmarked amount is a row total. Multiple unmarked amounts leave the total unknown and
   * preserve earlier tokens in the label for review. Only counts from 1 to 99 are applied. Weights
   * stay in the label with one inventory row.
   */
  fun parse(lines: List<ReceiptLine>): List<ReceiptItem> {
    val rows = mutableListOf<MutableList<ReceiptLine>>()
    for (line in lines.sortedBy { it.top }) {
      val row = rows.lastOrNull()
      if (row != null && line.top < row.minOf { it.bottom }) row.add(line)
      else rows.add(mutableListOf(line))
    }
    val result = mutableListOf<ReceiptItem>()
    var label: PrintedRow? = null
    var candidate: Candidate? = null
    for (linesInRow in rows) {
      val row = PrintedRow(linesInRow)
      val rowText = row.text.replaceFirst(leadingBarcode, "").replaceFirst(marker, "")
      if (total.containsMatchIn(rowText)) break
      if (header.containsMatchIn(rowText) ||
          summary.containsMatchIn(rowText) ||
          negativeAmount.containsMatchIn(rowText)) {
        label = null
        candidate = null
        continue
      }
      val parsed = parseRow(rowText)
      val weightDetail = weightOnly.matches(parsed.name)
      if (parsed.name.any(Char::isLetter) && !weightDetail) {
        if (parsed.hasMoney) {
          result += ReceiptItem(parsed.reviewName(), parsed.price, parsed.quantity ?: 1)
          candidate = Candidate(result.lastIndex, row, parsed)
          label = null
        } else {
          label = row
          candidate = null
        }
        continue
      }
      val previousLabel = label
      val previousItem = candidate
      if (parsed.hasMoney &&
          previousLabel != null &&
          row.follows(previousLabel, parsed.hasCount || weightDetail)) {
        val labelData = parseRow(previousLabel.text)
        val explicitCount = if (parsed.hasCount) parsed.quantity else labelData.quantity
        val name =
            if (weightDetail) "${labelData.reviewName()} ${parsed.name}" else labelData.reviewName()
        result +=
            ReceiptItem(parsed.reviewName(name), parsed.priceFor(explicitCount), explicitCount ?: 1)
      } else if (previousItem != null &&
          (parsed.hasCount || weightDetail) &&
          row.follows(previousItem.row, true)) {
        val old = result[previousItem.index]
        val previous = previousItem.parsed
        val explicitCount = if (parsed.hasCount) parsed.quantity else previous.quantity
        val unitPrice = if (parsed.hasMoney) parsed.unitPrice else previous.unitPrice
        val conflict =
            previous.printedTotal && parsed.printedTotal && old.priceCents != parsed.price
        result[previousItem.index] =
            old.copy(
                name =
                    parsed.reviewName(if (weightDetail) "${old.name} ${parsed.name}" else old.name),
                quantity = explicitCount ?: 1,
                priceCents =
                    when {
                      conflict -> null
                      previous.printedTotal -> old.priceCents
                      parsed.printedTotal -> parsed.price
                      else -> explicitCount?.let { unitPrice?.times(it) }
                    })
      }
      // One immediate detail may augment a product; orphan prices never reuse a consumed label.
      label = null
      candidate = null
    }
    return result
  }

  private class PrintedRow(lines: List<ReceiptLine>) {
    val text = lines.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
    val left = lines.minOf { it.left }
    val top = lines.minOf { it.top }
    val bottom = lines.maxOf { it.bottom }

    fun follows(previous: PrintedRow, detail: Boolean): Boolean {
      val height = maxOf(bottom - top, previous.bottom - previous.top, 1)
      return top - previous.bottom <= MAX_ROW_GAP_HEIGHTS * height &&
          (!detail || kotlin.math.abs(left - previous.left) <= MAX_DETAIL_INDENT_HEIGHTS * height)
    }
  }

  private data class Candidate(val index: Int, val row: PrintedRow, val parsed: ParsedRow)

  private data class ParsedRow(
      val name: String,
      val quantity: Int?,
      val uncertainCount: String,
      val hasCount: Boolean,
      val hasMoney: Boolean,
      val price: Long?,
      val printedTotal: Boolean,
      val unitPrice: Long?,
  ) {
    fun reviewName(label: String = name): String =
        listOf(uncertainCount, label.replaceFirst(leadingBarcode, "").replaceFirst(marker, ""))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

    fun priceFor(count: Int?): Long? =
        if (printedTotal) price else count?.let { unitPrice?.times(it) }
  }

  private fun parseRow(raw: String): ParsedRow {
    val text = raw.replaceFirst(leadingBarcode, "").replaceFirst(marker, "")
    val counts =
        count
            .findAll(text)
            .filterNot {
              it.value.trimEnd().last().lowercaseChar() in "x×*" &&
                  packageSize.containsMatchIn(text.substring(it.range.last + 1))
            }
            .toList()
    val amounts =
        amount
            .findAll(text)
            .filter { money ->
              counts.none {
                money.range.first <= it.range.last && it.range.first <= money.range.last
              } && !measurementSuffix.containsMatchIn(text.substring(money.range.last + 1))
            }
            .toList()
    val quantity =
        counts.singleOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf {
          it in 1..MAX_SCANNED_QUANTITY
        }
    val unitAmounts =
        amounts.filter { money ->
          counts.any {
            it.range.last < money.range.first &&
                text.substring(it.range.last + 1, money.range.first).isBlank() &&
                it.value.trimEnd().last().lowercaseChar() in "x×*"
          } ||
              unitPrefix.containsMatchIn(text.take(money.range.first)) ||
              unitSuffix.containsMatchIn(text.substring(money.range.last + 1))
        }
    val totals = amounts - unitAmounts.toSet()
    val rowTotal = totals.singleOrNull()?.cents()
    val price =
        when {
          totals.size == 1 && unitAmounts.size <= 1 -> rowTotal
          totals.isEmpty() && unitAmounts.size == 1 && quantity != null ->
              unitAmounts.single().cents()?.times(quantity)
          else -> null
        }
    val suffixes =
        amounts.mapNotNull { money ->
          unitSuffix.find(text.substring(money.range.last + 1))?.let {
            (money.range.last + 1 + it.range.first)..(money.range.last + 1 + it.range.last)
          }
        }
    val tail =
        amounts.lastOrNull()?.let { money ->
          text
              .substring(money.range.last + 1)
              .takeIf { taxSuffix.matches(it) }
              ?.let { (money.range.last + 1)..text.lastIndex }
        }
    val remove =
        (unitAmounts + totals.takeLast(1)).map { it.range } +
            counts.map { it.range } +
            suffixes +
            listOfNotNull(tail) +
            amounts.mapNotNull { money -> unitPrefix.find(text.take(money.range.first))?.range }
    val cleaned =
        text
            .mapIndexed { index, char -> if (remove.any { index in it }) ' ' else char }
            .joinToString("")
    return ParsedRow(
        cleaned.trim().replace(spaces, " "),
        quantity,
        if (quantity == null) counts.joinToString(" ") { it.value.trim() } else "",
        counts.isNotEmpty(),
        amounts.isNotEmpty(),
        price,
        totals.isNotEmpty(),
        unitAmounts.singleOrNull()?.cents())
  }

  // Seven whole digits and at most 99 units keep multiplication well below Long.MAX_VALUE.
  private fun MatchResult.cents(): Long? =
      groupValues[1]
          .takeIf { it.length <= MAX_WHOLE_DIGITS }
          ?.toLong()
          ?.let { it * CENTS_PER_UNIT + groupValues[2].toLong() }
}
