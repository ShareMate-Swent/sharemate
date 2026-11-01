// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceAmountsTest {
  @Test
  fun positiveAmountsAreConvertedExactlyWithoutFloatingPointRounding() {
    val examples =
        mapOf(
            "0.01" to 1L,
            "0.1" to 10L,
            "1" to 100L,
            "1.0" to 100L,
            "12.50" to 1250L,
            " 12.50\t" to 1250L,
            "00012.50" to 1250L,
            "92233720368547758" to Long.MAX_VALUE - 7,
            "92233720368547758.06" to Long.MAX_VALUE - 1,
            "92233720368547758.07" to Long.MAX_VALUE)
    examples.forEach { (input, cents) ->
      assertEquals(input, cents, FinanceAmounts.parseCents(input))
    }
    assertEquals("CHF", FinanceAmounts.CURRENCY_CODE)
  }

  @Test
  fun invalidAndNonpositiveAmountsAreRejectedWithoutRoundingOrLocaleGuessing() {
    val invalid =
        listOf(
            "",
            " ",
            "0",
            "00",
            "0.0",
            "0.00",
            "-1.00",
            "+1.00",
            ".01",
            "1.",
            "0.001",
            "1.000",
            "1,00",
            "1,000.00",
            "1 000.00",
            "1e2",
            "NaN",
            "Infinity",
            "١.٠٠",
            "１.００",
            "1\n.00",
            "1.00CHF")
    invalid.forEach { assertNull(it, FinanceAmounts.parseCents(it)) }
  }

  @Test
  fun amountsAboveLongCentsOrWholeNumberCapacityAreRejected() {
    for (input in
        listOf(
            "92233720368547758.08",
            "92233720368547758.99",
            "92233720368547759",
            "9223372036854775808",
            "9".repeat(100))) {
      assertNull(input, FinanceAmounts.parseCents(input))
    }
  }
}
