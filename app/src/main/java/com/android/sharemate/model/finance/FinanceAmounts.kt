// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/**
 * Current finance-model assumption: one household uses CHF, represented as two-decimal integer
 * cents. This is not a currency-conversion policy and does not change existing receipt storage.
 */
object FinanceAmounts {
  const val CURRENCY_CODE = "CHF"
  const val DECIMAL_SCALE = 2
  internal const val CENTS_PER_UNIT = 100L
  private val INPUT = Regex("[0-9]+(?:\\.[0-9]{1,$DECIMAL_SCALE})?")

  /**
   * Parses a positive payment amount exactly, returning null for invalid syntax or Long overflow.
   * Outer whitespace is trimmed; ASCII digits with an optional dot and one or two fractional digits
   * are accepted (e.g. `12`, `12.5`, `12.50`). Signs, grouping, commas and exponents are rejected.
   * Zero is invalid and no rounding occurs. Payment validation must enforce any outstanding-debt
   * limit separately.
   */
  fun parseCents(input: String): Long? {
    val normalized = input.trim()
    if (!INPUT.matches(normalized)) return null
    val parts = normalized.split('.', limit = 2)
    val whole = parts[0].toLongOrNull() ?: return null
    val fraction = if (parts.size == 1) 0L else parts[1].padEnd(DECIMAL_SCALE, '0').toLong()
    if (whole > (Long.MAX_VALUE - fraction) / CENTS_PER_UNIT) return null
    return (whole * CENTS_PER_UNIT + fraction).takeIf { it > 0 }
  }
}
