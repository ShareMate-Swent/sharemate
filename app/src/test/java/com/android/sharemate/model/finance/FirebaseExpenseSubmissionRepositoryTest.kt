// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.ReceiptCorrection
import com.android.sharemate.model.staging.StagedItem
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.*

class FirebaseExpenseSubmissionRepositoryTest {
  @Test
  fun correctionSendsOriginalAndRevisedRowsAndChecksItsAcknowledgement() = runTest {
    val id = "12345678-1234-1234-1234-123456789abc"
    val original =
        InventorySubmission(id, "author", "home", listOf(StagedItem("row", "Milk", 2, 100)))
    val revised =
        InventorySubmission(
            id,
            "author",
            "home",
            listOf(StagedItem("row", "Oat milk", 3, 200)),
            expense = SharedExpense(id, "home", "payer", 200, mapOf("author" to 200L)))
    val correction = ReceiptCorrection("edit-id", original, revised, "Courses")
    val wire =
        mapOf(
            "operationId" to "edit-id",
            "ownerId" to "author",
            "title" to "Courses",
            "originalExpense" to null,
            "originalRows" to
                listOf(
                    mapOf("id" to "row", "name" to "Milk", "quantity" to 2, "priceCents" to "100")),
            "rows" to
                listOf(
                    mapOf(
                        "id" to "row",
                        "name" to "Oat milk",
                        "quantity" to 3,
                        "priceCents" to "200")),
            "expense" to
                mapOf(
                    "id" to id,
                    "householdId" to "home",
                    "paidBy" to "payer",
                    "totalAmountCents" to "200",
                    "shares" to mapOf("author" to "200")))
    `when`(functions.getHttpsCallable("correctReceipt")).thenReturn(callable)
    val response =
        HttpsCallableResult::class
            .java
            .getConstructor(Any::class.java)
            .newInstance(mapOf("operationId" to "edit-id"))
    `when`(callable.call(wire)).thenReturn(Tasks.forResult(response))
    repository.correctReceipt(correction)
    verify(callable).call(wire)
    val wrong =
        HttpsCallableResult::class
            .java
            .getConstructor(Any::class.java)
            .newInstance(mapOf("operationId" to "other"))
    `when`(callable.call(wire)).thenReturn(Tasks.forResult(wrong))
    assertThrows(IllegalStateException::class.java) {
      runTest { repository.correctReceipt(correction) }
    }
  }

  private val functions = mock(FirebaseFunctions::class.java)
  private val callable = mock(HttpsCallableReference::class.java)
  private val repository: ExpenseSubmissionRepository =
      FirebaseExpenseSubmissionRepository(functions)
  private val expense =
      SharedExpense(
          "operation",
          "household",
          "payer",
          Long.MAX_VALUE,
          mapOf("member" to Long.MAX_VALUE, "payer" to 0L),
      )
  private val payload =
      mapOf(
          "id" to "operation",
          "householdId" to "household",
          "paidBy" to "payer",
          "totalAmountCents" to Long.MAX_VALUE.toString(),
          "shares" to mapOf("member" to Long.MAX_VALUE.toString(), "payer" to "0"),
      )

  init {
    `when`(functions.getHttpsCallable("recordExpense")).thenReturn(callable)
  }

  private fun respond(data: Any?) {
    val result = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(data)
    `when`(callable.call(payload)).thenReturn(Tasks.forResult(result))
  }

  @Test
  fun retriesPreserveIdentityAndExactAmounts() = runTest {
    respond(payload)
    assertSame(expense, repository.recordExpense(expense))
    assertSame(expense, repository.recordExpense(expense))
    verify(callable, times(2)).call(payload)
    assertEquals("europe-west6", FirebaseExpenseSubmissionRepository.REGION)
  }

  @Test
  fun malformedAcknowledgementsRemainUncertain() {
    for (response in listOf(null, "ok", payload - "shares", payload + ("paidBy" to "other"))) {
      respond(response)
      assertThrows(IllegalStateException::class.java) {
        runTest { repository.recordExpense(expense) }
      }
    }
  }

  @Test
  fun transportFailureAndCancellationRemainUncertain() {
    for (error in listOf(IOException("Lost response"), CancellationException("Cancelled"))) {
      `when`(callable.call(payload)).thenReturn(Tasks.forException(error))
      val failure = assertThrows(error.javaClass) { runTest { repository.recordExpense(expense) } }
      assertSame(error, failure)
    }
  }

  @Test
  fun onlyConclusiveCallableRejectionsAreTranslated() {
    val rejected =
        setOf(
            "INVALID_ARGUMENT",
            "PERMISSION_DENIED",
            "UNAUTHENTICATED",
            "FAILED_PRECONDITION",
            "ALREADY_EXISTS",
        )
    for (code in FirebaseFunctionsException.Code.values()) {
      val error = mock(FirebaseFunctionsException::class.java)
      `when`(error.code).thenReturn(code)
      `when`(callable.call(payload)).thenReturn(Tasks.forException(error))
      val failure =
          assertThrows(Exception::class.java) { runTest { repository.recordExpense(expense) } }
      if (code.name in rejected) {
        assertEquals(ExpenseRejectedException::class.java, failure.javaClass)
        assertSame(error, failure.cause)
        val expected =
            when (code.name) {
              "ALREADY_EXISTS" -> ExpenseRejectionReason.Conflict
              "PERMISSION_DENIED" -> ExpenseRejectionReason.PermissionDenied
              "INVALID_ARGUMENT" -> ExpenseRejectionReason.Invalid
              "UNAUTHENTICATED" -> ExpenseRejectionReason.Unauthenticated
              else -> ExpenseRejectionReason.FailedPrecondition
            }
        assertEquals(expected, (failure as ExpenseRejectedException).reason)
      } else assertSame(error, failure)
    }
  }
}
