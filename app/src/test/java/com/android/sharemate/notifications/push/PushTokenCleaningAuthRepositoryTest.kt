// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenCleaningAuthRepositoryTest {
  private val events = mutableListOf<String>()
  private val lockHeldAtSignOut = mutableListOf<Boolean>()
  private var signedInUser: String? = "alice"
  private var deletion: suspend () -> Unit = { events += "delete" }
  private var saving: suspend () -> Unit = { events += "save" }

  /** The lock every registrar shares, as the factory does in production. */
  private val operations = Mutex()

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
          lockHeldAtSignOut += operations.isLocked
          signedInUser = null
        }
      }

  private val tokens =
      object : PushTokenRepository {
        override suspend fun saveToken(userId: String, deviceId: String, token: String) = saving()

        override suspend fun deleteToken(userId: String, deviceId: String) = deletion()
      }

  private fun registrar() =
      PushTokenRegistrar({ signedInUser }, tokens, "device-1", operations) { "token" }

  private fun TestScope.repository() =
      PushTokenCleaningAuthRepository(
          delegate = delegate,
          currentUser = { signedInUser },
          registrar = registrar(),
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
  fun signsOutBeforeReleasingTheLockEvenWhenTheRemovalTimesOut() = runTest {
    deletion = { CompletableDeferred<Unit>().await() }

    repository().signOut()
    runCurrent()
    advanceTimeBy(TIMEOUT + 1)
    runCurrent()

    assertEquals(listOf("signOut"), events)
    assertEquals(listOf(true), lockHeldAtSignOut)
  }

  @Test
  fun aRegistrationWaitingDuringATimedOutRemovalSavesNothing() = runTest {
    deletion = { CompletableDeferred<Unit>().await() }
    repository().signOut()
    runCurrent()
    launch { registrar().register("token-2") }
    runCurrent()

    advanceTimeBy(TIMEOUT + 1)
    runCurrent()

    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun signsOutUnderTheLockWhenTheRemovalSucceeds() = runTest {
    repository().signOut()
    runCurrent()

    assertEquals(listOf(true), lockHeldAtSignOut)
  }

  @Test
  fun signsOutAnywayWhenAStuckRegistrationKeepsTheLockTaken() = runTest {
    saving = { CompletableDeferred<Unit>().await() }
    backgroundScope.launch { registrar().register("token-2") }
    runCurrent()
    repository().signOut()
    runCurrent()
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
  fun aRegistrationStartingDuringTheRemovalSavesNothingAfterTheSignOut() = runTest {
    val removal = CompletableDeferred<Unit>()
    deletion = {
      removal.await()
      events += "delete"
    }
    repository().signOut()
    runCurrent()

    launch { registrar().register("token-2") }
    runCurrent()
    assertTrue("the registration must wait for the sign-out", "save" !in events)

    removal.complete(Unit)
    runCurrent()

    assertEquals(listOf("delete", "signOut"), events)
  }

  @Test
  fun aRegistrationAlreadyRunningFinishesBeforeTheRemoval() = runTest {
    val saved = CompletableDeferred<Unit>()
    saving = {
      saved.await()
      events += "save"
    }
    launch { registrar().register("token-2") }
    runCurrent()
    repository().signOut()
    runCurrent()
    assertTrue("the removal must wait for the registration", events.isEmpty())

    saved.complete(Unit)
    runCurrent()

    assertEquals(listOf("save", "delete", "signOut"), events)
  }

  @Test
  fun aStuckRegistrationDelaysTheSignOutButNeverBlocksItForever() = runTest {
    saving = { CompletableDeferred<Unit>().await() }
    backgroundScope.launch { registrar().register("token-2") }
    runCurrent()
    repository().signOut()
    runCurrent()
    assertTrue(events.isEmpty())

    advanceTimeBy(TIMEOUT + 1)
    runCurrent()

    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun awaitSignOutReturnsAtOnceWhenNoSignOutIsPending() = runTest {
    repository().awaitSignOut()

    assertTrue(events.isEmpty())
  }

  @Test
  fun awaitSignOutOnlyReturnsOnceTheSignOutHasHappened() = runTest {
    deletion = { CompletableDeferred<Unit>().await() }
    val repository = repository()
    repository.signOut()
    var returned = false
    launch {
      repository.awaitSignOut()
      returned = true
    }

    runCurrent()
    assertFalse(returned)

    advanceTimeBy(TIMEOUT + 1)
    runCurrent()
    assertTrue(returned)
    assertEquals(listOf("signOut"), events)
  }

  @Test
  fun awaitingSignOutWaitsAfterClearingTheCredentials() = runTest {
    val repository = repository()
    repository.signOut()

    repository.awaitingSignOut { events += "clear" }()

    assertEquals(listOf("clear", "delete", "signOut"), events)
  }

  @Test
  fun awaitingSignOutStillWaitsWhenClearingTheCredentialsFails() = runTest {
    deletion = { CompletableDeferred<Unit>().await() }
    val repository = repository()
    repository.signOut()
    var failure: Exception? = null
    launch {
      try {
        repository.awaitingSignOut { throw IllegalStateException("provider down") }()
      } catch (error: IllegalStateException) {
        failure = error
      }
    }

    runCurrent()
    assertEquals(null, failure)

    advanceTimeBy(TIMEOUT + 1)
    runCurrent()
    assertEquals("provider down", failure?.message)
    assertEquals(listOf("signOut"), events)
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
