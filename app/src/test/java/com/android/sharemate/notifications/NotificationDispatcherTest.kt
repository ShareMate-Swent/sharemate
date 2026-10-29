// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDispatcherTest {
  private class RecordingPublisher : NotificationPublisher {
    val published = mutableListOf<AppNotification>()

    override fun publish(notification: AppNotification) {
      published += notification
    }
  }

  private fun notification(key: String) =
      AppNotification(key, AppNotificationChannel.EXPIRATION, "title", "text", "sharemate://app/x")

  private val publisher = RecordingPublisher()

  @Test
  fun publishesEveryNotificationOfEverySource() = runTest {
    val dispatcher =
        NotificationDispatcher(
            listOf(
                NotificationSource { listOf(notification("a"), notification("b")) },
                NotificationSource { listOf(notification("c")) }),
            publisher)

    val result = dispatcher.dispatch()

    assertEquals(listOf("a", "b", "c"), publisher.published.map { it.key })
    assertEquals(DispatchResult(published = 3, failedSources = 0), result)
    assertTrue(result.isSuccess)
  }

  @Test
  fun aFailingSourceDoesNotBlockTheOthers() = runTest {
    val dispatcher =
        NotificationDispatcher(
            listOf(
                NotificationSource { throw IllegalStateException("boom") },
                NotificationSource { listOf(notification("ok")) }),
            publisher)

    val result = dispatcher.dispatch()

    assertEquals(listOf("ok"), publisher.published.map { it.key })
    assertEquals(DispatchResult(published = 1, failedSources = 1), result)
    assertFalse(result.isSuccess)
  }

  @Test
  fun noSourcesPublishNothing() = runTest {
    val result = NotificationDispatcher(emptyList(), publisher).dispatch()

    assertTrue(publisher.published.isEmpty())
    assertTrue(result.isSuccess)
  }

  @Test
  fun cancellationIsNotSwallowed() = runTest {
    val dispatcher =
        NotificationDispatcher(
            listOf(NotificationSource { throw CancellationException("stop") }), publisher)

    try {
      dispatcher.dispatch()
      throw AssertionError("Expected cancellation to propagate")
    } catch (expected: CancellationException) {
      assertEquals("stop", expected.message)
    }
  }
}
