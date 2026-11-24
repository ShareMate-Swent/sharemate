// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.*
import org.junit.Test

class InventoryPurchaseValidationTest {
  private val rows = listOf(StagedItem("milk", "Edited milk", 2, 1000))
  private val shares = mapOf("alice" to 500L, "bob" to 500L)

  @Test
  fun journalRejectsInconsistentPurchasePayloadAndProgress() {
    val id = "12345678-1234-1234-1234-123456789abc"
    val expense = SharedExpense(id, "home", "alice", 1000, shares)
    val recordedByBob = InventorySubmission(id, "bob", "home", rows, expense = expense)
    assertEquals("bob", recordedByBob.ownerId)
    assertEquals("alice", recordedByBob.expense?.paidBy)
    val invalid =
        listOf<() -> InventorySubmission>(
            { InventorySubmission(id, "alice", "other", rows, expense = expense) },
            {
              InventorySubmission(
                  java.util.UUID.randomUUID().toString(), "alice", "home", rows, expense = expense)
            },
            { InventorySubmission(id, "alice", "home", rows, draft = true, expense = expense) },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = null) }, expense = expense)
            },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = 999) }, expense = expense)
            },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = 1001) }, expense = expense)
            },
            {
              InventorySubmission(
                  id,
                  "alice",
                  "home",
                  rows,
                  expense =
                      SharedExpense(
                          id, "home", "alice", 100_000_001, mapOf("alice" to 100_000_001L)))
            },
            {
              InventorySubmission(
                  id,
                  "alice",
                  "home",
                  rows,
                  expense = expense,
                  expenseProgress = ExpenseProgress.CONFIRMED)
            },
            { InventorySubmission(id, "alice", "home", rows, inventoryConfirmed = true) })
    invalid.forEach { assertTrue(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
  }
}
