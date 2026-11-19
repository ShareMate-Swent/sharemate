// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PushTokenRegistrarTest {
  private class RecordingTokenRepository(var failure: Exception? = null) : PushTokenRepository {
    val saved = mutableListOf<Triple<String, String, String>>()
    val deleted = mutableListOf<Pair<String, String>>()

    override suspend fun saveToken(userId: String, deviceId: String, token: String) {
      failure?.let { throw it }
      saved += Triple(userId, deviceId, token)
    }

    override suspend fun deleteToken(userId: String, deviceId: String) {
      failure?.let { throw it }
      deleted += userId to deviceId
    }
  }

  private val tokens = RecordingTokenRepository()
  private var fetches = 0
  private var fetchResult: suspend () -> String = { "current-token" }

  private fun registrar(userId: String?) =
      PushTokenRegistrar(
          currentUser = { userId },
          tokenRepository = tokens,
          deviceId = DEVICE_ID,
          operations = Mutex(),
          fetchToken = {
            fetches++
            fetchResult()
          })

  @Test
  fun savesTheKnownTokenForTheSignedInUserWithoutReadingFcm() = runTest {
    val outcome = registrar("bob").register("token-2")

    assertEquals(listOf(Triple("bob", DEVICE_ID, "token-2")), tokens.saved)
    assertEquals(0, fetches)
    assertTrue(outcome)
  }

  @Test
  fun savesTheCurrentFcmTokenWhenNoTokenIsKnown() = runTest {
    val outcome = registrar("bob").register(null)

    assertEquals(listOf(Triple("bob", DEVICE_ID, "current-token")), tokens.saved)
    assertTrue(outcome)
  }

  @Test
  fun doesNothingWhenSignedOutAndAsksForNoRetry() = runTest {
    val outcome = registrar(null).register("token-2")

    assertTrue(tokens.saved.isEmpty())
    assertEquals(0, fetches)
    assertTrue(outcome)
  }

  @Test
  fun aFailingTokenReadAsksForARetryAndSavesNothing() = runTest {
    fetchResult = { throw IllegalStateException("no token yet") }

    val outcome = registrar("bob").register(null)

    assertFalse(outcome)
    assertTrue(tokens.saved.isEmpty())
  }

  @Test
  fun aFailingSaveAsksForARetry() = runTest {
    tokens.failure = IllegalStateException("boom")

    assertFalse(registrar("bob").register("token-2"))
  }

  @Test
  fun aRejectedTokenAsksForARetryInsteadOfCrashing() = runTest {
    tokens.failure = IllegalArgumentException("blank")

    assertFalse(registrar("bob").register("token-2"))
  }

  @Test
  fun cancellationWhileReadingTheTokenIsNotSwallowed() = runTest {
    fetchResult = { throw CancellationException("stopped") }

    assertCancelled { registrar("bob").register(null) }
  }

  @Test
  fun cancellationWhileSavingIsNotSwallowed() = runTest {
    tokens.failure = CancellationException("stopped")

    assertCancelled { registrar("bob").register("token-2") }
  }

  @Test
  fun unregisterThenRemovesTheTokenOfTheGivenUserBeforeRunningTheFollowUp() = runTest {
    val order = mutableListOf<String>()
    val outcome =
        registrar("bob").unregisterThen("alice") { order += "afterwards:${tokens.deleted.size}" }

    assertEquals(listOf("alice" to DEVICE_ID), tokens.deleted)
    assertEquals(listOf("afterwards:1"), order)
    assertTrue(outcome)
  }

  @Test
  fun aFailingRemovalReportsFalseButStillRunsTheFollowUp() = runTest {
    tokens.failure = IllegalStateException("denied")
    var ran = false

    val outcome = registrar("bob").unregisterThen("alice") { ran = true }

    assertFalse(outcome)
    assertTrue(ran)
  }

  @Test
  fun cancellationDuringTheRemovalIsNotSwallowed() = runTest {
    tokens.failure = CancellationException("stopped")

    assertCancelled { registrar("bob").unregisterThen("bob") {} }
  }

  @Test
  fun theSignedInUserIsReadAgainBeforeSavingSoASignOutInBetweenWritesNothing() = runTest {
    val answers = ArrayDeque(listOf<String?>("bob", null))
    val registrar =
        PushTokenRegistrar(
            currentUser = { answers.removeFirst() },
            tokenRepository = tokens,
            deviceId = DEVICE_ID,
            operations = Mutex(),
            fetchToken = { "current-token" })

    val outcome = registrar.register("token-2")

    assertTrue(outcome)
    assertTrue(tokens.saved.isEmpty())
  }

  private suspend fun assertCancelled(action: suspend () -> Unit) {
    try {
      action()
      fail("Expected the cancellation to propagate")
    } catch (_: CancellationException) {
      // expected
    }
  }

  private companion object {
    const val DEVICE_ID = "device-1"
  }
}
