package com.android.sharemate.model.finance

import org.junit.Assert.*
import org.junit.Test

class SettlementCalculationTest {
  private val expense = SharedExpense("e", "home", "bob", 1500, mapOf("alice" to 1500L))

  private fun payment(id: String, amount: Long) = Settlement(id, "home", "alice", "bob", amount)

  private fun calculate(vararg payments: Settlement) =
      BalanceCalculator.calculate(
          HouseholdFinanceSnapshot("home", listOf(expense), payments.toList()),
          listOf("alice", "bob", "zero"))

  @Test
  fun partialThenFullSettlementConservesCents() {
    val partial = calculate(payment("partial", 600))
    assertEquals(mapOf("alice" to -900L, "bob" to 900L, "zero" to 0L), partial.netBalancesCents)
    assertEquals(listOf(Debt("alice", "bob", 900)), partial.debts)
    val full = calculate(payment("partial", 600), payment("rest", 900))
    assertTrue(full.debts.isEmpty())
    assertTrue(full.netBalancesCents.values.all { it == 0L })
  }

  @Test
  fun centBoundariesAndOrderRemainExact() {
    for (amount in listOf(1499L, 1500L, 1501L)) {
      val result = calculate(payment("p", amount))
      assertEquals(amount - 1500, result.netBalancesCents["alice"])
      assertEquals(1500 - amount, result.netBalancesCents["bob"])
    }
    assertEquals(
        calculate(payment("a", 1), payment("b", 599)),
        calculate(payment("b", 599), payment("a", 1)))
  }

  @Test
  fun historicalSettlementOnlyParticipantsAndLargeIntermediateTotalsAreRetained() {
    val settlements =
        listOf(
            Settlement("a", "home", "former", "bob", Long.MAX_VALUE),
            Settlement("b", "home", "former", "bob", Long.MAX_VALUE),
            Settlement("c", "home", "bob", "former", Long.MAX_VALUE))
    val result =
        BalanceCalculator.calculate(
            HouseholdFinanceSnapshot("home", emptyList(), settlements), emptyList())
    assertEquals(
        mapOf("bob" to -Long.MAX_VALUE, "former" to Long.MAX_VALUE), result.netBalancesCents)
    assertEquals(listOf(Debt("bob", "former", Long.MAX_VALUE)), result.debts)
    assertThrows(ArithmeticException::class.java) {
      BalanceCalculator.calculate(
          HouseholdFinanceSnapshot("home", emptyList(), settlements.take(2)), emptyList())
    }
  }

  @Test
  fun multiCreditorSettlementRematchesRemainingDebtsDeterministically() {
    val expenses =
        listOf(expense, SharedExpense("other", "home", "carol", 700, mapOf("dave" to 700L)))
    val result =
        BalanceCalculator.calculate(
            HouseholdFinanceSnapshot("home", expenses, listOf(payment("paid", 600))), emptyList())
    assertEquals(listOf(Debt("alice", "bob", 900), Debt("dave", "carol", 700)), result.debts)
    assertEquals(0L, result.netBalancesCents.values.sum())
  }
}
