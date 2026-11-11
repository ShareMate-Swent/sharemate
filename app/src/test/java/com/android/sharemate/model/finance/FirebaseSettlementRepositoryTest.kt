// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FirebaseSettlementRepositoryTest {
  private val functions = mock(FirebaseFunctions::class.java)
  private val callable = mock(HttpsCallableReference::class.java)
  private val repository = FirebaseSettlementRepository(functions)
  private val settlement =
      Settlement("operation", "household", "payer", "recipient", Long.MAX_VALUE)
  private val payload =
      mapOf(
          "id" to "operation",
          "householdId" to "household",
          "payerId" to "payer",
          "recipientId" to "recipient",
          "amountCents" to Long.MAX_VALUE.toString(),
      )

  init {
    `when`(functions.getHttpsCallable("recordSettlement")).thenReturn(callable)
  }

  @Test
  fun retriesSendTheSameOperationAndPreserveLongCents() = runTest {
    respondWith(payload)

    assertEquals(settlement, repository.recordSettlement(settlement))
    assertEquals(settlement, repository.recordSettlement(settlement))
    verify(callable, times(2)).call(payload)
  }

  @Test
  fun rejectsAnUnexpectedAcknowledgement() {
    respondWith(payload + ("amountCents" to "1"))

    assertThrows(IllegalStateException::class.java) {
      runTest { repository.recordSettlement(settlement) }
    }
  }

  @Test
  fun propagatesAnUncertainOutcomeForTheCallerToRetry() {
    val error = IOException("Lost acknowledgement")
    `when`(callable.call(payload)).thenReturn(Tasks.forException(error))

    val failure =
        assertThrows(IOException::class.java) {
          runTest { repository.recordSettlement(settlement) }
        }
    assertSame(error, failure)
  }

  private fun respondWith(data: Map<String, String>) {
    // Firebase exposes data as a final field and keeps the constructor internal in Kotlin.
    val response = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(data)
    `when`(callable.call(payload)).thenReturn(Tasks.forResult(response))
  }

  @Test
  fun distinguishesDefinitiveRejectionsFromAmbiguousCallableFailures() {
    val rejected =
        setOf(
            "INVALID_ARGUMENT",
            "PERMISSION_DENIED",
            "UNAUTHENTICATED",
            "FAILED_PRECONDITION",
            "ALREADY_EXISTS")
    for (code in FirebaseFunctionsException.Code.values()) {
      val error = mock(FirebaseFunctionsException::class.java)
      `when`(error.code).thenReturn(code)
      `when`(callable.call(payload)).thenReturn(Tasks.forException(error))
      val failure =
          assertThrows(Exception::class.java) {
            runTest { repository.recordSettlement(settlement) }
          }
      if (code.name in rejected)
          assertEquals(SettlementRejectedException::class.java, failure.javaClass)
      else assertSame(error, failure)
    }
  }
}
