// Co-authored-by: Codex
package com.android.sharemate.ui.auth

import com.android.sharemate.model.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleAuthViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = GoogleRepository()
  private lateinit var vm: AuthViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    vm = AuthViewModel(repository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun pickerCannotStartBeforeRestorationOrInsideExistingSession() = runTest {
    // Construct inside the test so restoration has not run on its dispatcher yet.
    vm = AuthViewModel(repository)
    assertTrue(vm.state.value.isRestoringSession)
    var pickerCalls = 0
    vm.signInWithGoogle {
      pickerCalls++
      "token"
    }
    repository.session.value = AuthSession("existing", "student@example.org")
    advanceUntilIdle()
    vm.signInWithGoogle {
      pickerCalls++
      "token"
    }
    assertEquals(0, pickerCalls)
    assertTrue(repository.tokens.isEmpty())
    assertEquals("existing", vm.state.value.session?.uid)
  }

  @Test
  fun cancelledPickerIsSilentAndAllowsRetry() = runTest {
    advanceUntilIdle()
    vm.signInWithGoogle { null }
    assertFalse(vm.state.value.isLoading)
    assertNull(vm.state.value.error)
    assertTrue(repository.tokens.isEmpty())
    val attempt = launch { vm.signInWithGoogle { "retry-token" } }
    advanceUntilIdle()
    assertEquals(listOf("retry-token"), repository.tokens)
    repository.session.value = AuthSession("google-user", "student@example.org")
    advanceUntilIdle()
    attempt.join()
    assertEquals("google-user", vm.state.value.session?.uid)
  }

  @Test
  fun pickerAndExchangeFailuresRemainSafeAndRetryable() = runTest {
    advanceUntilIdle()
    for (error in listOf(AuthError.GOOGLE_UNAVAILABLE, AuthError.ACCOUNT_CONFLICT)) {
      vm.signInWithGoogle { throw AuthException(error) }
      assertEquals(AuthUiError.valueOf(error.name), vm.state.value.error)
      assertFalse(vm.state.value.isLoading)
    }
    vm.signInWithGoogle { throw IllegalStateException("private token response") }
    assertEquals(AuthUiError.GOOGLE_UNAVAILABLE, vm.state.value.error)
    vm.signInWithGoogle { "  " }
    assertEquals(AuthUiError.INVALID_CREDENTIALS, vm.state.value.error)
    assertTrue(repository.tokens.isEmpty())
    repository.failure = AuthException(AuthError.NETWORK)
    vm.signInWithGoogle { "token" }
    advanceUntilIdle()
    assertEquals(AuthUiError.NETWORK, vm.state.value.error)
    assertNull(vm.state.value.session)
    assertFalse(vm.state.value.isLoading)
  }

  @Test
  fun googleWaitsForPickerAndSessionAndRejectsConcurrentActions() = runTest {
    advanceUntilIdle()
    val picker = CompletableDeferred<String?>()
    vm.updatePassword("old-secret")
    val attempt = launch { vm.signInWithGoogle { picker.await() } }
    advanceUntilIdle()
    assertTrue(vm.state.value.isLoading)
    var secondPickerCalls = 0
    vm.signInWithGoogle {
      secondPickerCalls++
      "duplicate"
    }
    vm.submit()
    vm.selectMode(AuthMode.SIGN_UP)
    assertEquals(0, secondPickerCalls)
    assertEquals(AuthMode.LOGIN, vm.state.value.mode)
    picker.complete(" exact-token ")
    advanceUntilIdle()
    assertEquals(listOf(" exact-token "), repository.tokens)
    assertTrue(attempt.isCompleted)
    assertTrue(vm.state.value.isLoading)
    assertNull(vm.state.value.session)
    repository.session.value = AuthSession("google-user", "student@example.org")
    advanceUntilIdle()
    attempt.join()
    assertFalse(vm.state.value.isLoading)
    assertEquals("", vm.state.value.password)
    assertNull(vm.state.value.error)
  }

  @Test
  fun coroutineCancellationPropagatesWithoutUiError() = runTest {
    advanceUntilIdle()
    try {
      vm.signInWithGoogle { throw CancellationException("picker closed") }
      fail("Coroutine cancellation must propagate")
    } catch (_: CancellationException) {}
    assertFalse(vm.state.value.isLoading)
    assertNull(vm.state.value.error)
    assertTrue(repository.tokens.isEmpty())
  }

  @Test
  fun logoutClosesFirebaseSessionBeforeBestEffortCredentialCleanup() = runTest {
    val cleanup = CompletableDeferred<Unit>()
    vm = AuthViewModel(repository, clearCredentialState = { cleanup.await() })
    repository.session.value = AuthSession("google-user", "student@example.org")
    advanceUntilIdle()
    vm.signOut()
    assertEquals(1, repository.signOutCalls)
    assertNull(repository.session.value)
    advanceUntilIdle()
    assertNull(vm.state.value.session)
    assertTrue(vm.state.value.isLoading)
    cleanup.completeExceptionally(IllegalStateException("private provider response"))
    advanceUntilIdle()
    assertFalse(vm.state.value.isLoading)
    assertNull(vm.state.value.error)
  }

  private class GoogleRepository : AuthRepository {
    override val session = MutableStateFlow<AuthSession?>(null)
    val tokens = mutableListOf<String>()
    var failure: Exception? = null
    var signOutCalls = 0

    override suspend fun signInWithGoogle(idToken: String) {
      tokens += idToken
      failure?.let { throw it }
    }

    override suspend fun signIn(email: String, password: String) =
        error("Unexpected password login")

    override suspend fun signUp(email: String, password: String) = error("Unexpected signup")

    override fun signOut() {
      signOutCalls++
      session.value = null
    }
  }
}
