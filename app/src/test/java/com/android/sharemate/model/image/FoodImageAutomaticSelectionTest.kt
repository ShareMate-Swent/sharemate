// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_MAX_ATTEMPTS
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_RETRY_DELAY_MS
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_TIMEOUT_MS
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoodImageAutomaticSelectionTest {
  @Test
  fun temporaryFailuresRetryAfterDelayAndPreserveImageCredits() = runTest {
    for (failure in
        listOf(
            Failure(Reason.NETWORK),
            Failure(Reason.HTTP, 408),
            Failure(Reason.HTTP, 429),
            Failure(Reason.HTTP, 500),
            Failure(Reason.HTTP, 504),
            Failure(Reason.HTTP, 599))) {
      val calls = mutableListOf<Pair<Long, String>>()
      val startedAt = currentTime
      val selector =
          FoodImageSelector(
              repository { title ->
                calls += currentTime to title
                if (calls.size == 1) failure else Found(image())
              })

      val selected = selector.selectAutomatic("  soupe  ")

      assertEquals(FoodImageSelection.RemoteImage(image()), selected)
      assertEquals(
          listOf(startedAt to "  soupe  ", startedAt + IMAGE_LOOKUP_RETRY_DELAY_MS to "  soupe  "),
          calls)
    }
  }

  @Test
  fun thrownNetworkFailureCanRecoverOnTheNextAttempt() = runTest {
    var calls = 0
    val selector =
        FoodImageSelector(
            repository {
              if (++calls == 1) throw IOException("Temporary network failure")
              Found(image())
            })

    assertEquals(FoodImageSelection.RemoteImage(image()), selector.selectAutomatic("soupe"))
    assertEquals(2, calls)
    assertEquals(IMAGE_LOOKUP_RETRY_DELAY_MS, currentTime)
  }

  @Test
  fun attemptTimeoutCancelsItsCallThenRetriesWithANewDeadline() = runTest {
    var calls = 0
    var firstCallCancelled = false
    val selector =
        FoodImageSelector(
            repository {
              if (++calls == 1) {
                try {
                  awaitCancellation()
                } finally {
                  firstCallCancelled = true
                }
              }
              Found(image())
            })
    val selection = async { selector.selectAutomatic("soupe") }
    runCurrent()
    advanceTimeBy(IMAGE_LOOKUP_TIMEOUT_MS)
    runCurrent()
    assertTrue(firstCallCancelled)
    assertEquals(1, calls)
    assertFalse(selection.isCompleted)
    advanceTimeBy(IMAGE_LOOKUP_RETRY_DELAY_MS)
    runCurrent()

    assertEquals(FoodImageSelection.RemoteImage(image()), selection.await())
    assertEquals(2, calls)
    assertEquals(IMAGE_LOOKUP_TIMEOUT_MS + IMAGE_LOOKUP_RETRY_DELAY_MS, currentTime)
  }

  @Test
  fun temporaryFailureExhaustionHasExactlyThreeAttemptsAndTwoWaits() = runTest {
    val calls = mutableListOf<Long>()
    val selector =
        FoodImageSelector(
            repository {
              calls += currentTime
              Failure(Reason.HTTP, 504)
            })

    assertEquals(FoodImageSelection.Generic, selector.selectAutomatic("soupe"))
    assertEquals(IMAGE_LOOKUP_MAX_ATTEMPTS, calls.size)
    assertEquals(listOf(0L, 2_000L, 4_000L), calls)
    assertEquals(4_000L, currentTime)
  }

  @Test
  fun timeoutExhaustionIsBoundedAndCancelsEveryAttempt() = runTest {
    var calls = 0
    var cancelledCalls = 0
    val selector =
        FoodImageSelector(
            repository {
              calls++
              try {
                awaitCancellation()
              } finally {
                cancelledCalls++
              }
            })

    assertEquals(FoodImageSelection.Generic, selector.selectAutomatic("soupe"))
    assertEquals(3, calls)
    assertEquals(calls, cancelledCalls)
    assertEquals(64_000L, currentTime)
  }

  @Test
  fun terminalResultsDoNotRetryOrWait() = runTest {
    for (result in
        listOf(
            NotFound,
            Failure(Reason.EMPTY_TITLE),
            Failure(Reason.INVALID_RESPONSE),
            Failure(Reason.HTTP, 400),
            Failure(Reason.HTTP, 401),
            Failure(Reason.HTTP, 403),
            Failure(Reason.HTTP, 404),
            Failure(Reason.HTTP, 301),
            Failure(Reason.HTTP))) {
      var calls = 0
      val selector =
          FoodImageSelector(
              repository {
                calls++
                result
              })

      assertEquals(FoodImageSelection.Generic, selector.selectAutomatic("soupe"))
      assertEquals(1, calls)
      assertEquals(0L, currentTime)
    }
  }

  @Test
  fun cancellingDuringRetryWaitPreventsTheNextCall() = runTest {
    var calls = 0
    val selector =
        FoodImageSelector(
            repository {
              calls++
              Failure(Reason.NETWORK)
            })
    val selection = async { selector.selectAutomatic("soupe") }
    runCurrent()
    advanceTimeBy(IMAGE_LOOKUP_RETRY_DELAY_MS - 1)

    selection.cancelAndJoin()
    advanceTimeBy(IMAGE_LOOKUP_RETRY_DELAY_MS * 5)
    runCurrent()

    assertTrue(selection.isCancelled)
    assertEquals(1, calls)
  }

  @Test
  fun externalTimeoutCancelsTheActiveCallWithoutRetry() = runTest {
    var calls = 0
    var callCancelled = false
    val selector =
        FoodImageSelector(
            repository {
              calls++
              try {
                awaitCancellation()
              } finally {
                callCancelled = true
              }
            })

    try {
      withTimeout(1_000) { selector.selectAutomatic("soupe") }
      throw AssertionError("External cancellation must propagate")
    } catch (_: CancellationException) {
      assertTrue(callCancelled)
      assertEquals(1, calls)
      assertEquals(1_000L, currentTime)
    }
  }

  @Test
  fun unexpectedFailurePropagatesWithoutRetry() = runTest {
    var calls = 0
    val failure = IllegalStateException("Unexpected failure")
    val selector =
        FoodImageSelector(
            repository {
              calls++
              throw failure
            })

    try {
      selector.selectAutomatic("soupe")
      throw AssertionError("Unexpected failure must propagate")
    } catch (actual: IllegalStateException) {
      assertEquals(failure.javaClass, actual.javaClass)
      assertEquals(failure.message, actual.message)
      assertEquals(1, calls)
      assertEquals(0L, currentTime)
    }
  }

  private fun repository(search: suspend (String) -> FoodImageSearchResult): FoodImageRepository =
      object : FoodImageRepository {
        override suspend fun searchByTitle(title: String): FoodImageSearchResult = search(title)
      }

  private fun image() =
      FoodImage(
          url = "https://images.example.org/food.jpg",
          author = "Jane Doe",
          sourceName = "Wikimedia Commons",
          sourceUrl = "https://example.org/photos/42",
          title = "Soupe",
          license = "cc0",
          licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
          licenseVersion = "1.0",
          authorUrl = "https://example.org/jane")
}
