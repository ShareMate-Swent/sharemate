// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.push.PushTokenRepository
import com.android.sharemate.ui.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reproduces a sign-out whose token removal is still pending, with the real [AuthViewModel] on top:
 * the same account must not be able to sign back in until the sign-out has finished, otherwise the
 * late sign-out would end the new session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenSignOutFlowTest {
  private val dispatcher = StandardTestDispatcher()
  private val sessions = MutableStateFlow<AuthSession?>(AuthSession(USER, EMAIL))
  private val signIns = mutableListOf<String>()
  private val signOuts = mutableListOf<String?>()

  private val delegate =
      object : AuthRepository {
        override val session: Flow<AuthSession?> = sessions

        override suspend fun signIn(email: String, password: String) {
          signIns += email
          sessions.value = AuthSession(USER, email)
        }

        override suspend fun signUp(email: String, password: String, displayName: String) = Unit

        override suspend fun signInWithGoogle(idToken: String) = Unit

        override fun signOut() {
          signOuts += sessions.value?.uid
          sessions.value = null
        }
      }

  private val tokens =
      object : PushTokenRepository {
        override suspend fun saveToken(userId: String, deviceId: String, token: String) = Unit

        override suspend fun deleteToken(userId: String, deviceId: String) {
          CompletableDeferred<Unit>().await()
        }
      }

  private val cleaning =
      PushTokenCleaningAuthRepository(
          delegate = delegate,
          currentUser = { sessions.value?.uid },
          registrar =
              PushTokenRegistrar({ sessions.value?.uid }, tokens, "device-1", Mutex()) { "token" },
          scope = CoroutineScope(dispatcher),
          cleanupTimeoutMillis = TIMEOUT)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun AuthViewModel.fillLogin() {
    updateEmail(EMAIL)
    updatePassword("secret")
  }

  @Test
  fun theSameAccountCannotSignBackInWhileTheSignOutIsPending() =
      runTest(dispatcher) {
        val viewModel = AuthViewModel(cleaning, cleaning.awaitingSignOut {})
        runCurrent()

        viewModel.signOut()
        runCurrent()
        viewModel.fillLogin()
        viewModel.submit()
        runCurrent()

        assertTrue(viewModel.state.value.isLoading)
        assertTrue(signIns.isEmpty())
        assertTrue(signOuts.isEmpty())
      }

  @Test
  fun signingBackInIsPossibleOnceTheSignOutHasFinishedAndKeepsTheNewSession() =
      runTest(dispatcher) {
        val viewModel = AuthViewModel(cleaning, cleaning.awaitingSignOut {})
        runCurrent()
        viewModel.signOut()
        runCurrent()

        advanceTimeBy(TIMEOUT + 1)
        runCurrent()
        assertEquals(listOf<String?>(USER), signOuts)
        assertFalse(viewModel.state.value.isLoading)

        viewModel.fillLogin()
        viewModel.submit()
        runCurrent()

        assertEquals(listOf(EMAIL), signIns)
        assertEquals(USER, sessions.value?.uid)
        assertEquals(listOf<String?>(USER), signOuts)
        assertNull(viewModel.state.value.error)
      }

  private companion object {
    const val USER = "alice"
    const val EMAIL = "alice@example.org"
    const val TIMEOUT = 3_000L
  }
}
