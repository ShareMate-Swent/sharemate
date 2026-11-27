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
  fun deletionRequiresMatchingAcknowledgementAndPreservesTransportFailure() = runTest {
    val wire = mapOf("householdId" to "home", "id" to "receipt")
    `when`(functions.getHttpsCallable("deleteReceipt")).thenReturn(callable)
    for (response in listOf(mapOf("id" to "receipt"), mapOf("id" to "other"), null)) {
      val result =
          HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(response)
      `when`(callable.call(wire)).thenReturn(Tasks.forResult(result))
      if (response == mapOf("id" to "receipt")) repository.deleteReceipt("home", "receipt")
      else
          assertEquals(
              IllegalStateException::class.java,
              runCatching { repository.deleteReceipt("home", "receipt") }
                  .exceptionOrNull()
                  ?.javaClass)
    }
    val failure = IOException("Lost deletion response")
    `when`(callable.call(wire)).thenReturn(Tasks.forException(failure))
    assertSame(
        failure, runCatching { repository.deleteReceipt("home", "receipt") }.exceptionOrNull())
  }

  @Test
  fun correctionsPreserveExistingExpenseAndUnknownOriginalPrice() = runTest {
    val id = "12345678-1234-1234-1234-123456789abc"
    val oldExpense = SharedExpense(id, "home", "author", 100, mapOf("author" to 100L))
    val original =
        InventorySubmission(
            id, "author", "home", listOf(StagedItem("row", "Milk", 1, 100)), expense = oldExpense)
    val revised =
        InventorySubmission(
            id,
            "author",
            "home",
            listOf(
                StagedItem("row", "Milk", 1, 200),
                StagedItem("extra", "Bread", includedInExpense = false)),
            expense = SharedExpense(id, "home", "author", 200, mapOf("author" to 200L)))
    `when`(functions.getHttpsCallable("correctReceipt")).thenReturn(callable)
    val result =
        HttpsCallableResult::class
            .java
            .getConstructor(Any::class.java)
            .newInstance(mapOf("operationId" to "revision"))
    `when`(callable.call(any())).thenReturn(Tasks.forResult(result))
    repository.correctReceipt(
        ReceiptCorrection("revision", original, revised, "Groceries", originalTitle = "Old title"))
    val sent = org.mockito.ArgumentCaptor.forClass(Map::class.java)
    verify(callable).call(sent.capture())
    assertEquals("Old title", sent.value["originalTitle"])
    assertEquals(
        mapOf(
            "id" to id,
            "householdId" to "home",
            "paidBy" to "author",
            "totalAmountCents" to "100",
            "shares" to mapOf("author" to "100")),
        sent.value["originalExpense"])
    assertEquals(
        listOf(mapOf("id" to "row", "name" to "Milk", "quantity" to 1, "priceCents" to "100")),
        sent.value["originalRows"])
    assertEquals(
        listOf(
            mapOf("id" to "row", "name" to "Milk", "quantity" to 1, "priceCents" to "200"),
            mapOf(
                "id" to "extra",
                "name" to "Bread",
                "quantity" to 1,
                "priceCents" to null,
                "includedInExpense" to false)),
        sent.value["rows"])
    val unknown = InventorySubmission(id, "author", "home", listOf(StagedItem("row", "Milk")))
    repository.correctReceipt(ReceiptCorrection("revision", unknown, revised, "Groceries"))
    verify(callable, times(2)).call(sent.capture())
    assertEquals(
        listOf(mapOf("id" to "row", "name" to "Milk", "quantity" to 1, "priceCents" to null)),
        sent.value["originalRows"])
    val inventoryOnly =
        InventorySubmission(
            id, "author", "home", revised.items.map { it.copy(includedInExpense = false) })
    repository.correctReceipt(ReceiptCorrection("revision", original, inventoryOnly, "Groceries"))
    verify(callable, times(3)).call(sent.capture())
    assertEquals(null, sent.value["expense"])
    assertEquals(mapOf("id" to id, "householdId" to "home"), sent.value["receipt"])
  }

  @Test
  fun correctionRejectsIdentityChangesDraftsAndMissingExpense() {
    val id = "12345678-1234-1234-1234-123456789abc"
    fun receipt(
        receiptId: String = id,
        owner: String = "author",
        home: String = "home",
        draft: Boolean = false,
        priced: Boolean = true
    ) =
        InventorySubmission(
            receiptId,
            owner,
            home,
            listOf(StagedItem("row", "Milk", 1, 100)),
            draft = draft,
            expense =
                if (draft || !priced) null
                else SharedExpense(receiptId, home, owner, 100, mapOf(owner to 100L)))
    val original = receipt()
    for (replacement in
        listOf(
            receipt(receiptId = "12345678-1234-1234-1234-123456789abd"),
            receipt(owner = "other"),
            receipt(home = "other"),
            receipt(draft = true),
            receipt(priced = false))) {
      assertThrows(IllegalArgumentException::class.java) {
        ReceiptCorrection("revision", original, replacement, "Groceries")
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      ReceiptCorrection("revision", receipt(draft = true), original, "Groceries")
    }
    val unsupported =
        object : ExpenseSubmissionRepository {
          override suspend fun recordExpense(expense: SharedExpense) = expense
        }
    assertThrows(IllegalStateException::class.java) {
      runTest { unsupported.deleteReceipt("home", id) }
    }
    assertThrows(IllegalStateException::class.java) {
      runTest { unsupported.correctReceipt(ReceiptCorrection("revision", original, original, "")) }
    }
  }

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
            "originalTitle" to "",
            "originalRevision" to 0L,
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
    assertEquals(
        IllegalStateException::class.java,
        runCatching { repository.correctReceipt(correction) }.exceptionOrNull()?.javaClass)
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
