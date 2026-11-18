// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenCleaningAuthRepositoryTest {
  private val events = mutableListOf<String>()
  private var signedInUser: String? = "alice"
  private var deletion: suspend () -> Unit = { events += "delete" }

  private val delegate =
      object : AuthRepository {
        override val session: Flow<AuthSession?> = emptyFlow()

        override suspend fun signIn(email: String, password: String) {
          events += "signIn:$email"
        }

        override suspend fun signUp(email: String, password: String, displayName: String) = Unit

        override suspend fun signInWithGoogle(idToken: String) = Unit

        override fun signOut() {
          events += "signOut"
        }
      }

  private val tokens =
      object : PushTokenRepository {
        override suspend fun saveToken(userId: String, deviceId: String, token: String) = Unit

        override suspend fun deleteToken(userId: String, deviceId: String) = deletion()
      }

  private fun TestScope.repository() =
      PushTokenCleaningAuthRepository(
          delegate = delegate,
          currentUser = { signedInUser },
          registrar = PushTokenRegistrar({ signedInUser }, tokens, "device-1") { "token" },
          scope = this,
          cleanupTimeoutMillis = TIMEOUT)

  @Test
  fun removesTheTokenBeforeSigningOut() = runTest {
    repository().signOut()
    runCurrent()

    assertEquals(listOf("delete", "signOut"), events)
  }

  @Test
  fun signsOutImmediatelyWhenNobodyIsSignedIn() = runTest {
    signedInUser = null

    repository().signOut()

    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun stillSignsOutWhenTheRemovalFails() = runTest {
    deletion = { throw IllegalStateException("denied") }

    repository().signOut()
    runCurrent()

    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun stopsWaitingForARemovalThatNeverCompletesOffline() = runTest {
    deletion = { CompletableDeferred<Unit>().await() }

    repository().signOut()
    runCurrent()
    assertTrue(events.isEmpty())

    advanceTimeBy(TIMEOUT + 1)
    runCurrent()
    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun doesNotSignOutSomeoneWhoSignedInMeanwhile() = runTest {
    deletion = {
      signedInUser = "bob"
      events += "delete"
    }

    repository().signOut()
    runCurrent()

    assertEquals(listOf("delete"), events)
  }

  @Test
  fun delegatesEverythingElseUntouched() = runTest {
    repository().signIn("a@example.org", "pw")

    assertEquals(listOf("signIn:a@example.org"), events)
  }

  private companion object {
    const val TIMEOUT = 3_000L
  }
}
