// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.firebase.firestore.DocumentSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SharedExpenseFirestoreMappingTest {
  @Test
  fun mapsExplicitLongSharesIncludingZeroHistoricalParticipantsAndMaximumAmounts() {
    val mapped = document().toSharedExpense("home")
    assertEquals(
        SharedExpense("expense", "home", "former", 100, mapOf("former" to 0L, "bob" to 100L)),
        mapped)
    val maximum =
        document(
                fields() +
                    mapOf(
                        "totalAmountCents" to Long.MAX_VALUE,
                        "shares" to mapOf("bob" to Long.MAX_VALUE)))
            .toSharedExpense("home")
    assertEquals(Long.MAX_VALUE, maximum.totalAmountCents)
    assertEquals(mapOf("bob" to Long.MAX_VALUE), maximum.shares)
  }

  @Test
  fun missingExtraOrMismatchedFieldsCannotDisappearFromFinancialTotals() {
    val invalid =
        fields().keys.map { fields() - it } +
            listOf(
                fields() + ("unexpected" to true),
                fields() + ("id" to "another"),
                fields() + ("householdId" to "other"),
                fields() + ("paidBy" to null),
                fields() + ("paidBy" to 1L),
                fields() + ("paidBy" to " "))
    for (values in invalid) rejects(values)
    rejects(null)
  }

  @Test
  fun floatingPointStringsNullAndInvalidShareSchemasAreRejectedWithoutCoercion() {
    for (amount in listOf(null, 100.0, 100, "100", 0L, -1L)) {
      rejects(fields() + ("totalAmountCents" to amount))
    }
    for (shares in
        listOf(
            null,
            "bob",
            emptyMap<String, Long>(),
            mapOf(1L to 100L),
            mapOf("bob" to 100.0),
            mapOf("bob" to 100),
            mapOf("bob" to null),
            mapOf(" " to 100L),
            mapOf("bob" to -1L),
            mapOf("bob" to 99L),
            mapOf("bob" to 101L))) {
      rejects(fields() + ("shares" to shares))
    }
    rejects(
        fields() +
            mapOf(
                "totalAmountCents" to 1L,
                "shares" to linkedMapOf("a" to Long.MAX_VALUE, "b" to Long.MAX_VALUE, "c" to 3L)))
  }

  private fun rejects(values: Map<String, Any?>?) {
    assertThrows(IllegalArgumentException::class.java) { document(values).toSharedExpense("home") }
  }

  private fun fields(): Map<String, Any?> =
      mapOf(
          "id" to "expense",
          "householdId" to "home",
          "paidBy" to "former",
          "totalAmountCents" to 100L,
          "shares" to mapOf("former" to 0L, "bob" to 100L))

  private fun document(values: Map<String, Any?>? = fields()): DocumentSnapshot {
    val document = mock(DocumentSnapshot::class.java)
    `when`(document.id).thenReturn("expense")
    `when`(document.data).thenAnswer { values }
    return document
  }
}
