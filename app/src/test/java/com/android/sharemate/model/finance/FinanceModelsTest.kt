// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FinanceModelsTest {
  @Test
  fun snapshotSyncStatusParticipatesInEqualityHashingAndDiagnostics() = runBlocking {
    val synced = HouseholdFinanceSnapshot("home", emptyList(), emptyList())
    val cached = HouseholdFinanceSnapshot("home", emptyList(), emptyList(), isSynced = false)
    val sameCache = HouseholdFinanceSnapshot("home", emptyList(), emptyList(), isSynced = false)
    assertTrue(synced.isSynced)
    assertFalse(cached.isSynced)
    assertNotEquals(synced, cached)
    assertEquals(cached, sameCache)
    assertEquals(cached.hashCode(), sameCache.hashCode())
    assertNotEquals(synced.hashCode(), cached.hashCode())
    assertEquals(
        listOf(cached, synced),
        listOf(cached, sameCache, synced).asFlow().distinctUntilChanged().toList())
    assertTrue(cached.toString().contains("isSynced=false"))
  }

  @Test
  fun explicitSharesConserveTotalAndMayExcludePayerOrIncludeZeroShares() {
    val shared = expense(shares = linkedMapOf("alice" to 0L, "bob" to 300L))
    assertEquals("expense", shared.id)
    assertEquals("home", shared.householdId)
    assertEquals("alice", shared.paidBy)
    assertEquals(300L, shared.totalAmountCents)
    assertEquals(mapOf("alice" to 0L, "bob" to 300L), shared.shares)
    assertEquals(mapOf("bob" to 300L), expense(shares = mapOf("bob" to 300L)).shares)
    val maximum =
        expense(
            total = Long.MAX_VALUE,
            shares = linkedMapOf("alice" to Long.MAX_VALUE - 1, "bob" to 1L))
    assertEquals(Long.MAX_VALUE, maximum.totalAmountCents)
  }

  @Test
  fun invalidExpenseIdentityAmountsAndSharesAreRejectedIncludingOverflow() {
    rejects(
        { expense(id = " ") },
        { expense(home = " ") },
        { expense(payer = " ") },
        { expense(total = 0) },
        { expense(total = -1) },
        { expense(total = Long.MIN_VALUE) },
        { expense(shares = emptyMap()) },
        { expense(shares = mapOf(" " to 300L)) },
        { expense(shares = mapOf("bob" to -1L)) },
        { expense(shares = mapOf("bob" to 299L)) },
        { expense(shares = mapOf("bob" to 301L)) },
        { expense(total = Long.MAX_VALUE, shares = linkedMapOf("a" to Long.MAX_VALUE, "b" to 1L)) },
        {
          expense(
              total = 1,
              shares = linkedMapOf("a" to Long.MAX_VALUE, "b" to Long.MAX_VALUE, "c" to 3L))
        })
  }

  @Test
  fun validatedSharesCannotBeChangedViaInputMapOrExposedMapEntries() {
    val source = linkedMapOf("alice" to 100L, "bob" to 200L)
    val shared = expense(shares = source)
    val originalHash = shared.hashCode()
    source["bob"] = 999L
    assertThrows(UnsupportedOperationException::class.java) {
      (shared.shares as MutableMap<String, Long>)["bob"] = 999L
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (shared.shares.entries.first() as MutableMap.MutableEntry<String, Long>).setValue(999L)
    }
    assertEquals(mapOf("alice" to 100L, "bob" to 200L), shared.shares)
    assertEquals(originalHash, shared.hashCode())
  }

  @Test
  fun directedDebtsAndSettlementsRetainPositiveBoundaryAmountsAndRetryIdentity() {
    for (amount in listOf(1L, Long.MAX_VALUE)) {
      val debt = Debt("bob", "alice", amount)
      assertEquals("bob", debt.debtorId)
      assertEquals("alice", debt.creditorId)
      assertEquals(amount, debt.amountCents)
      val payment = payment(amount = amount)
      assertEquals("payment", payment.id)
      assertEquals("home", payment.householdId)
      assertEquals("bob", payment.payerId)
      assertEquals("alice", payment.recipientId)
      assertEquals(amount, payment.amountCents)
      assertEquals(payment, payment.copy())
      assertNotEquals(payment, payment.copy(id = "new-payment"))
    }
  }

  @Test
  fun invalidDebtAndSettlementPartiesIdentityAndAmountsAreRejected() {
    rejects(
        { Debt(" ", "alice", 1) },
        { Debt("bob", " ", 1) },
        { Debt("alice", "alice", 1) },
        { payment(id = " ") },
        { payment(home = " ") },
        { payment(payer = " ") },
        { payment(recipient = " ") },
        { payment(payer = "alice") })
    for (amount in listOf(0L, -1L, Long.MIN_VALUE)) {
      rejects({ Debt("bob", "alice", amount) }, { payment(amount = amount) })
    }
  }

  @Test
  fun snapshotsKeepHistoricalParticipantsAndAllowEmptyLedgersAndIdsScopedByKind() {
    val empty = HouseholdFinanceSnapshot("home", emptyList(), emptyList())
    assertTrue(empty.expenses.isEmpty())
    assertTrue(empty.settlements.isEmpty())
    val shared = expense(id = "record", payer = "former-member")
    val settled = payment(id = "record", payer = "former-member")
    val snapshot = HouseholdFinanceSnapshot("home", listOf(shared), listOf(settled))
    assertEquals("home", snapshot.householdId)
    assertEquals(listOf(shared), snapshot.expenses)
    assertEquals(listOf(settled), snapshot.settlements)
  }

  @Test
  fun snapshotsRejectForeignHouseholdsAndDuplicateRecordIds() {
    rejects(
        { HouseholdFinanceSnapshot(" ", emptyList(), emptyList()) },
        { snapshot(expenses = listOf(expense(home = "other"))) },
        { snapshot(payments = listOf(payment(home = "other"))) },
        { snapshot(expenses = listOf(expense(), expense(payer = "bob"))) },
        { snapshot(payments = listOf(payment(), payment(amount = 200))) })
  }

  @Test
  fun snapshotsProtectValidatedListsFromSourceAndConsumerMutation() {
    val shared = expense()
    val settled = payment()
    val expenses = mutableListOf(shared)
    val payments = mutableListOf(settled)
    val ledger = snapshot(expenses, payments)
    val originalHash = ledger.hashCode()
    expenses.clear()
    payments.clear()
    assertThrows(UnsupportedOperationException::class.java) {
      (ledger.expenses as MutableList<SharedExpense>).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (ledger.settlements as MutableList<Settlement>).clear()
    }
    assertEquals(listOf(shared), ledger.expenses)
    assertEquals(listOf(settled), ledger.settlements)
    assertEquals(originalHash, ledger.hashCode())
  }

  @Test
  fun expenseValueEqualityIncludesIdentityAndAllFinancialFields() {
    val original = expense()
    val equivalent = expense(shares = linkedMapOf("bob" to 200L, "alice" to 100L))
    assertEquals(original, equivalent)
    assertEquals(original.hashCode(), equivalent.hashCode())
    assertNotEquals(original, null)
    assertNotEquals(original, payment())
    for (changed in
        listOf(
            expense(id = "new"),
            expense(home = "other"),
            expense(payer = "bob"),
            expense(total = 400, shares = mapOf("bob" to 400L)),
            expense(shares = mapOf("bob" to 300L)))) {
      assertNotEquals(original, changed)
    }
  }

  @Test
  fun equivalentSnapshotsDeduplicateButLedgerChangesRemainObservable() = runBlocking {
    val original = snapshot()
    val equivalent = snapshot(listOf(expense()), listOf(payment().copy()))
    val changedExpense = snapshot(expenses = listOf(expense(id = "new")))
    val changedPayment = snapshot(payments = listOf(payment(amount = 200)))
    val otherHousehold = HouseholdFinanceSnapshot("other", emptyList(), emptyList())
    val emitted =
        listOf(original, equivalent, changedExpense, changedPayment, otherHousehold)
            .asFlow()
            .distinctUntilChanged()
            .toList()
    assertEquals(listOf(original, changedExpense, changedPayment, otherHousehold), emitted)
    assertEquals(original.hashCode(), equivalent.hashCode())
    assertNotEquals(original, null)
    assertNotEquals(original, expense())
  }

  @Test
  fun expenseDiagnosticsIdentifyTheRecordPayerAmountAndExplicitShares() {
    val description = expense().toString()
    for (field in
        listOf(
            "SharedExpense(",
            "id=expense",
            "householdId=home",
            "paidBy=alice",
            "totalAmountCents=300",
            "shares={",
            "alice=100",
            "bob=200")) {
      assertTrue("Missing $field in $description", description.contains(field))
    }
  }

  @Test
  fun snapshotDiagnosticsIncludeNestedRecordsAndDistinguishEmptyLedgers() {
    val description = snapshot().toString()
    for (field in
        listOf(
            "HouseholdFinanceSnapshot(",
            "householdId=home",
            "expenses=[SharedExpense(",
            "paidBy=alice",
            "totalAmountCents=300",
            "settlements=[Settlement(",
            "id=payment",
            "payerId=bob",
            "recipientId=alice",
            "amountCents=100")) {
      assertTrue("Missing $field in $description", description.contains(field))
    }
    val empty = HouseholdFinanceSnapshot("home", emptyList(), emptyList()).toString()
    assertTrue(empty.contains("householdId=home"))
    assertTrue(empty.contains("expenses=[]"))
    assertTrue(empty.contains("settlements=[]"))
  }

  private fun expense(
      id: String = "expense",
      home: String = "home",
      payer: String = "alice",
      total: Long = 300,
      shares: Map<String, Long> = mapOf("alice" to 100L, "bob" to 200L)
  ) = SharedExpense(id, home, payer, total, shares)

  private fun payment(
      id: String = "payment",
      home: String = "home",
      payer: String = "bob",
      recipient: String = "alice",
      amount: Long = 100
  ) = Settlement(id, home, payer, recipient, amount)

  private fun snapshot(
      expenses: List<SharedExpense> = listOf(expense()),
      payments: List<Settlement> = listOf(payment())
  ) = HouseholdFinanceSnapshot("home", expenses, payments)

  private fun rejects(vararg invalid: () -> Any) {
    invalid.forEach { candidate ->
      assertThrows(IllegalArgumentException::class.java) { candidate() }
    }
  }
}
