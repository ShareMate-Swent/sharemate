// Co-authored-by: Codex
package com.android.sharemate.model.receipt

/** Vertical bounds allow OCR blocks for names and prices to be read as one printed row. */
internal data class ReceiptLine(val text: String, val left: Int, val top: Int, val bottom: Int)

data class ReceiptItem(val name: String, val priceCents: Long)

internal object ReceiptParser {
  private val amount =
      Regex("""(?<!\S)(?:CHF\s*)?(\d{1,7})[.,](\d{2})(?:\s*CHF)?$""", RegexOption.IGNORE_CASE)
  private val summary =
      Regex(
          """^(?:(?:sous[- ]?|sub[- ]?)?total|tva|mwst|vat|cash|carte|visa|mastercard|rendu|rabais|remise)\b""",
          RegexOption.IGNORE_CASE)
  private val quantityDetails = Regex("""^\d+\s*(?:PCS?\b|[x×*])""", RegexOption.IGNORE_CASE)
  private val leadingBarcode = Regex("""^\d[\d+\-]{7,15}\s+""")

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
        .takeWhile { !it.startsWith("TOTAL", ignoreCase = true) }
        .mapNotNull { text ->
          val match = amount.find(text)
          if (match == null) {
            previousLabel = text.takeIf { it.any(Char::isLetter) }
            return@mapNotNull null
          }
          val prefix = text.take(match.range.first).trim()
          val name =
              (if (prefix.isEmpty() || quantityDetails.containsMatchIn(prefix))
                      previousLabel.orEmpty()
                  else prefix)
                  .replaceFirst(leadingBarcode, "")
          previousLabel = null
          if (name.none(Char::isLetter) || summary.containsMatchIn(name)) return@mapNotNull null
          ReceiptItem(name, match.groupValues[1].toLong() * 100 + match.groupValues[2].toLong())
        }
  }
}
