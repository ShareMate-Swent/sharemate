// Co-authored-by: Codex
package com.android.sharemate.model.receipt

/** Vertical bounds allow OCR blocks for names and prices to be read as one printed row. */
internal data class ReceiptLine(val text: String, val left: Int, val top: Int, val bottom: Int)

data class ReceiptItem(val name: String, val priceCents: Long)

internal object ReceiptParser {
  private const val CENTS_PER_UNIT = 100L
  private val amount =
      Regex(
          """(?<!\S)(?:CHF\s*)?(\d{1,7})[.,](\d{2})(?:\s*CHF)?(?:\s+[A-Z0-9])?$""",
          RegexOption.IGNORE_CASE)
  private val total = Regex("""^total\b""", RegexOption.IGNORE_CASE)
  private val summary =
      Regex(
          """^(?:(?:sous[- ]?|sub[- ]?)?total|tva|mwst|vat|cash|carte|visa|mastercard|rendu|rabais|remise)\b""",
          RegexOption.IGNORE_CASE)
  private val quantityDetails = Regex("""^\d+\s*(?:PCS?\b|[x×*])""", RegexOption.IGNORE_CASE)
  private val leadingBarcode = Regex("""^\d[\d+\-]{7,15}\s+""")
  private val marker = Regex("""^(?:(?:SPZ|AKT)\s+)+""")
  private val header =
      Regex(
          """^(?:migros|totalenergies|date|caisse|caissier|kasse|cashier)\b""",
          RegexOption.IGNORE_CASE)

  fun parse(lines: List<ReceiptLine>): List<ReceiptItem> {
    val rows = mutableListOf<MutableList<ReceiptLine>>()
    for (line in lines.sortedBy { it.top }) {
      val row = rows.lastOrNull()
      if (row != null && line.top < row.minOf { it.bottom }) row.add(line)
      else rows.add(mutableListOf(line))
    }
    var previousLabel: String? = null
    val printedRows =
        rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim() }
    return printedRows
        .takeWhile { !total.containsMatchIn(it) }
        .mapNotNull { text ->
          val match = amount.find(text)
          if (match == null) {
            previousLabel = text.takeIf { it.any(Char::isLetter) && !header.containsMatchIn(it) }
            return@mapNotNull null
          }
          val prefix = text.take(match.range.first).trim().replaceFirst(quantityDetails, "").trim()
          val name =
              (if (prefix.none(Char::isLetter)) previousLabel.orEmpty() else prefix)
                  .replaceFirst(leadingBarcode, "")
                  .replaceFirst(marker, "")
          previousLabel = null
          if (name.none(Char::isLetter) || summary.containsMatchIn(name)) return@mapNotNull null
          ReceiptItem(
              name, match.groupValues[1].toLong() * CENTS_PER_UNIT + match.groupValues[2].toLong())
        }
  }
}
