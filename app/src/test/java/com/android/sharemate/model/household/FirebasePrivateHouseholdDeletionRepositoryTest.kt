// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FirebasePrivateHouseholdDeletionRepositoryTest {
  private val functions = mock(FirebaseFunctions::class.java)
  private val untimedCallable = mock(HttpsCallableReference::class.java)
  private val callable = mock(HttpsCallableReference::class.java)
  private val repository = FirebasePrivateHouseholdDeletionRepository(functions)
  private val payload = mapOf("householdId" to "owner:home")

  init {
    `when`(functions.getHttpsCallable("deletePrivateHousehold")).thenReturn(untimedCallable)
    `when`(untimedCallable.withTimeout(330L, TimeUnit.SECONDS)).thenReturn(callable)
  }

  @Test
  fun callsTheCallableWithTheHouseholdId() = runTest {
    respondWith(payload)

    repository.deletePrivateHousehold("owner:home")

    verify(untimedCallable).withTimeout(330L, TimeUnit.SECONDS)
    verify(callable).call(payload)
  }

  @Test
  fun callingAgainSendsTheSameRequestSoARetryIsSafe() = runTest {
    respondWith(payload)

    repository.deletePrivateHousehold("owner:home")
    repository.deletePrivateHousehold("owner:home")

    verify(callable, times(2)).call(payload)
  }

  @Test
  fun blankHouseholdIdIsRejectedBeforeAnyCall() {
    assertThrows(IllegalArgumentException::class.java) {
      runTest { repository.deletePrivateHousehold(" ") }
    }
    verify(functions, never()).getHttpsCallable("deletePrivateHousehold")
  }

  @Test
  fun anUnexpectedAcknowledgementIsAFailure() {
    respondWith(mapOf("householdId" to "another"))

    assertThrows(IllegalStateException::class.java) {
      runTest { repository.deletePrivateHousehold("owner:home") }
    }
  }

  @Test
  fun aServerRefusalPropagatesSoNothingLooksDeleted() {
    // The constructor is internal in Kotlin, so the exception is a mock.
    val refusal = mock(FirebaseFunctionsException::class.java)
    `when`(refusal.code).thenReturn(FirebaseFunctionsException.Code.PERMISSION_DENIED)
    `when`(callable.call(payload)).thenReturn(Tasks.forException(refusal))

    val failure =
        assertThrows(FirebaseFunctionsException::class.java) {
          runTest { repository.deletePrivateHousehold("owner:home") }
        }
    assertSame(refusal, failure)
  }

  @Test
  fun anUnreachableServerPropagatesForTheCallerToRetry() {
    val error = IOException("offline")
    `when`(callable.call(payload)).thenReturn(Tasks.forException(error))

    val failure =
        assertThrows(IOException::class.java) {
          runTest { repository.deletePrivateHousehold("owner:home") }
        }
    assertSame(error, failure)
  }

  @Test
  fun theFunctionsClientIsOnlyCreatedWhenAHouseholdIsDeletedAndThenReused() = runTest {
    respondWith(payload)
    var created = 0
    val lazyRepository = FirebasePrivateHouseholdDeletionRepository {
      created++
      functions
    }
    assertEquals(0, created)

    lazyRepository.deletePrivateHousehold("owner:home")
    lazyRepository.deletePrivateHousehold("owner:home")

    assertEquals(1, created)
  }

  @Test
  fun waitsLongerThanTheFunctionCanRun() {
    // The function stops after 300 seconds; giving up earlier would offer a retry too soon.
    assertTrue(FirebasePrivateHouseholdDeletionRepository.CALL_TIMEOUT_SECONDS > 300L)
  }

  @Test
  fun usesTheRegionOfTheOtherCallables() {
    assertEquals("europe-west6", FirebasePrivateHouseholdDeletionRepository.REGION)
  }

  private fun respondWith(data: Map<String, String>) {
    // Firebase exposes data as a final field and keeps the constructor internal in Kotlin.
    val response = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(data)
    `when`(callable.call(payload)).thenReturn(Tasks.forResult(response))
  }
}
