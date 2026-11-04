// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import java.math.BigInteger
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BalanceCalculatorTest {
  @Test
  fun emptyLedgersRetainZeroCurrentMembersOnceInLexicalOrder() {
    val result = calculate(emptyList(), listOf("bob", "alice", "bob"))
    assertEquals("home", result.householdId)
    assertEquals(listOf("alice", "bob"), result.netBalancesCents.keys.toList())
    assertEquals(mapOf("alice" to 0L, "bob" to 0L), result.netBalancesCents)
    assertTrue(result.debts.isEmpty())
    assertConserved(result)
    val noMembers = calculate(emptyList())
    assertTrue(noMembers.netBalancesCents.isEmpty())
    assertTrue(noMembers.debts.isEmpty())
  }

  @Test
  fun unevenSharesMultiplePayersAndHistoricalParticipantsProduceConservedDebts() {
    val result = calculate(multiPersonExpenses(), listOf("new", "dave", "bob", "alice", "carol"))
    assertEquals(
        mapOf(
            "alice" to 267L,
            "bob" to 267L,
            "carol" to -634L,
            "dave" to -400L,
            "former" to 500L,
            "new" to 0L),
        result.netBalancesCents)
    assertEquals(
        listOf(
            Debt("carol", "alice", 267),
            Debt("carol", "bob", 267),
            Debt("carol", "former", 100),
            Debt("dave", "former", 400)),
        result.debts)
    assertConserved(result)
  }

  @Test
  fun payerSelfConsumptionAndZeroShareParticipantsDoNotCreateDebts() {
    val result =
        calculate(
            listOf(expense("self", "alice", 100, linkedMapOf("alice" to 100L, "zero-share" to 0L))),
            listOf("new"))
    assertEquals(mapOf("alice" to 0L, "new" to 0L, "zero-share" to 0L), result.netBalancesCents)
    assertTrue(result.debts.isEmpty())
    assertConserved(result)
  }

  @Test
  fun reciprocalPurchasesAndMultiPersonCyclesCancelExactly() {
    val reciprocal = listOf(owed("one", "alice", "bob", 75), owed("two", "bob", "alice", 75))
    val cycle =
        listOf(
            owed("a", "alice", "bob", 101),
            owed("b", "bob", "carol", 101),
            owed("c", "carol", "alice", 101))
    for (expenses in listOf(reciprocal, cycle)) {
      val result = calculate(expenses)
      assertTrue(result.netBalancesCents.values.all { it == 0L })
      assertEquals(
          expenses.flatMap { listOf(it.paidBy) + it.shares.keys }.toSet(),
          result.netBalancesCents.keys)
      assertTrue(result.debts.isEmpty())
      assertConserved(result)
    }
  }

  @Test
  fun lexicalGreedyMatchingIsStableWithoutClaimingTheFewestDebtEdges() {
    val expenses = listOf(owed("x", "x", "b", 3), owed("y", "y", "c", 2), owed("z", "z", "a", 5))
    val result = calculate(expenses)
    // Matching a->z, b->x, c->y could use three edges; this contract uses lexical matching.
    assertEquals(
        listOf(Debt("a", "x", 3), Debt("a", "y", 2), Debt("b", "z", 3), Debt("c", "z", 2)),
        result.debts)
    assertConserved(result)
  }

  @Test
  fun expenseMemberAndShareIterationOrderDoNotChangeTheResult() {
    val original = multiPersonExpenses()
    val expected = calculate(original, listOf("new", "bob"))
    val reversed =
        original.reversed().map {
          expense(
              it.id,
              it.paidBy,
              it.totalAmountCents,
              it.shares.entries.reversed().associate { entry -> entry.key to entry.value })
        }
    val permutations = listOf(original, reversed, listOf(original[1], original[2], original[0]))
    for (input in permutations) {
      val result = calculate(input, listOf("bob", "new", "bob"))
      assertEquals(expected, result)
      assertEquals(expected.hashCode(), result.hashCode())
      assertEquals(expected.netBalancesCents.keys.toList(), result.netBalancesCents.keys.toList())
      assertConserved(result)
    }
  }

  @Test
  fun intermediateTotalsMayExceedLongWhenFinalBalancesRemainRepresentable() {
    val expenses =
        listOf(
            owed("one", "alice", "bob", Long.MAX_VALUE),
            owed("two", "alice", "bob", Long.MAX_VALUE),
            owed("three", "bob", "alice", Long.MAX_VALUE))
    for (input in listOf(expenses, expenses.reversed())) {
      val result = calculate(input)
      assertEquals(
          mapOf("alice" to Long.MAX_VALUE, "bob" to -Long.MAX_VALUE), result.netBalancesCents)
      assertEquals(listOf(Debt("bob", "alice", Long.MAX_VALUE)), result.debts)
      assertConserved(result)
    }
  }

  @Test
  fun longMinimumDebtorIsSplitIntoRepresentablePositiveDebtsWithoutNegationOverflow() {
    val result =
        calculate(
            listOf(
                owed("max", "alice", "bob", Long.MAX_VALUE), owed("last-cent", "carol", "bob", 1)))
    assertEquals(
        mapOf("alice" to Long.MAX_VALUE, "bob" to Long.MIN_VALUE, "carol" to 1L),
        result.netBalancesCents)
    assertEquals(
        listOf(Debt("bob", "alice", Long.MAX_VALUE), Debt("bob", "carol", 1)), result.debts)
    assertConserved(result)
  }

  @Test
  fun genuinelyOutOfRangePositiveAndNegativeFinalBalancesAreRejected() {
    val positiveOverflow =
        listOf(owed("max", "alice", "bob", Long.MAX_VALUE), owed("excess", "alice", "carol", 1))
    val negativeOverflow =
        listOf(owed("max", "alice", "bob", Long.MAX_VALUE), owed("excess", "carol", "bob", 2))
    for (expenses in listOf(positiveOverflow, negativeOverflow)) {
      assertThrows(ArithmeticException::class.java) { calculate(expenses) }
    }
  }

  @Test
  fun blankCurrentMembersAndUnsupportedSettlementsCannotBeSilentlyIgnored() {
    for (blank in listOf("", " \t")) {
      assertThrows(IllegalArgumentException::class.java) {
        calculate(emptyList(), listOf("alice", blank))
      }
    }
    val ledger =
        HouseholdFinanceSnapshot(
            "home",
            listOf(owed("expense", "alice", "bob", 100)),
            listOf(Settlement("payment", "home", "bob", "alice", 50)))
    assertThrows(IllegalArgumentException::class.java) {
      BalanceCalculator.calculate(ledger, listOf("alice", "bob"))
    }
  }

  @Test
  fun calculationDoesNotMutateInputsAndSummaryCollectionsCannotBeChanged() {
    val expenses = multiPersonExpenses()
    val ledger = HouseholdFinanceSnapshot("home", expenses, emptyList())
    val members = mutableListOf("new", "bob")
    val result = BalanceCalculator.calculate(ledger, members)
    assertEquals(listOf("new", "bob"), members)
    assertEquals(expenses, ledger.expenses)
    members += "later"
    assertFalse(result.netBalancesCents.containsKey("later"))
    val nets = result.netBalancesCents.toMutableMap()
    val debts = result.debts.toMutableList()
    val copied = BalanceSummary("home", nets, debts)
    nets.clear()
    debts.clear()
    assertEquals(result, copied)
    assertThrows(UnsupportedOperationException::class.java) {
      (copied.netBalancesCents as MutableMap<String, Long>)["alice"] = 0L
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (copied.netBalancesCents.entries.first() as MutableMap.MutableEntry<String, Long>).setValue(
          0L)
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (copied.debts as MutableList<Debt>).clear()
    }
    assertEquals(result, copied)
    assertConserved(copied)
  }

  @Test
  fun summariesDeduplicateEquivalentEmissionsButRetainFinancialAndHouseholdChanges() = runBlocking {
    val original = calculate(listOf(owed("one", "alice", "bob", 100)))
    val equivalent = calculate(listOf(owed("another-record", "alice", "bob", 100)))
    val amountChanged = calculate(listOf(owed("one", "alice", "bob", 101)))
    val householdChanged =
        BalanceCalculator.calculate(
            HouseholdFinanceSnapshot("other", emptyList(), emptyList()), emptyList())
    val greedy =
        calculate(listOf(owed("x", "x", "b", 3), owed("y", "y", "c", 2), owed("z", "z", "a", 5)))
    val alternateDebts =
        BalanceSummary(
            "home",
            greedy.netBalancesCents,
            listOf(Debt("a", "z", 5), Debt("b", "x", 3), Debt("c", "y", 2)))
    val emitted =
        listOf(original, equivalent, amountChanged, householdChanged)
            .asFlow()
            .distinctUntilChanged()
            .toList()
    assertEquals(listOf(original, amountChanged, householdChanged), emitted)
    assertEquals(original.hashCode(), equivalent.hashCode())
    assertNotEquals(greedy, alternateDebts)
    assertNotEquals(original, null)
    assertNotEquals(original, "home")
    val description = original.toString()
    for (field in
        listOf("householdId=home", "netBalancesCents=", "alice=100", "bob=-100", "debts=[Debt(")) {
      assertTrue("Missing $field in $description", description.contains(field))
    }
  }

  @Test
  fun crossHouseholdExpenseIsRejectedWhenConstructingCalculatorInput() {
    val foreign = SharedExpense("foreign", "other", "alice", 100, mapOf("bob" to 100L))
    // The snapshot enforces household coherence before the calculator can receive its input.
    assertThrows(IllegalArgumentException::class.java) {
      HouseholdFinanceSnapshot("home", listOf(foreign), emptyList())
    }
  }

  private fun multiPersonExpenses() =
      listOf(
          expense(
              "one", "alice", 1001, linkedMapOf("alice" to 334L, "bob" to 333L, "carol" to 334L)),
          expense("two", "bob", 600, linkedMapOf("alice" to 300L, "bob" to 0L, "carol" to 300L)),
          expense("three", "former", 500, linkedMapOf("alice" to 100L, "dave" to 400L)))

  private fun expense(id: String, payer: String, total: Long, shares: Map<String, Long>) =
      SharedExpense(id, "home", payer, total, shares)

  private fun owed(id: String, payer: String, participant: String, amount: Long) =
      expense(id, payer, amount, mapOf(participant to amount))

  private fun calculate(expenses: List<SharedExpense>, members: Collection<String> = emptyList()) =
      BalanceCalculator.calculate(HouseholdFinanceSnapshot("home", expenses, emptyList()), members)

  private fun assertConserved(summary: BalanceSummary) {
    val expected = summary.netBalancesCents.mapValues { BigInteger.valueOf(it.value) }
    assertEquals(BigInteger.ZERO, expected.values.fold(BigInteger.ZERO, BigInteger::add))
    val reconstructed = expected.mapValues { BigInteger.ZERO }.toMutableMap()
    for (debt in summary.debts) {
      assertTrue(debt.amountCents > 0)
      assertNotEquals(debt.debtorId, debt.creditorId)
      val amount = BigInteger.valueOf(debt.amountCents)
      reconstructed[debt.debtorId] = reconstructed.getValue(debt.debtorId) - amount
      reconstructed[debt.creditorId] = reconstructed.getValue(debt.creditorId) + amount
    }
    assertEquals(expected, reconstructed)
  }
}
