// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.finance.FinanceAmounts.CENTS_PER_UNIT
import com.android.sharemate.model.finance.FinanceAmounts.DECIMAL_SCALE

/** Result of reading a price typed by the user. */
sealed interface PriceInput {
  /** The field is empty: the item has no price. */
  data object Empty : PriceInput

  data class Valid(val cents: Long) : PriceInput

  data object Invalid : PriceInput
}

/** Converts prices between the text typed by the user and integer cents, without floats. */
object Prices {
  // Up to seven whole digits keeps every value far below Long.MAX_VALUE.
  private val pattern = Regex("""(\d{1,7})(?:[.,](\d{1,$DECIMAL_SCALE}))?""")

  /** Reads "3", "3.5", "3.50" or "3,50" (both separators are common on receipts). */
  fun parse(input: String): PriceInput {
    val text = input.trim()
    if (text.isEmpty()) return PriceInput.Empty
    val match = pattern.matchEntire(text) ?: return PriceInput.Invalid
    val whole = match.groupValues[1].toLong()
    val fraction = match.groupValues[2].padEnd(DECIMAL_SCALE, '0').toLong()
    return PriceInput.Valid(whole * CENTS_PER_UNIT + fraction)
  }

  /** Formats cents as "3.50", or an empty string when there is no price. */
  fun format(cents: Long?): String =
      if (cents == null) ""
      else
          "${cents / CENTS_PER_UNIT}.${(cents % CENTS_PER_UNIT).toString().padStart(DECIMAL_SCALE, '0')}"
}
