package com.android.sharemate.ui.auth

import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var repository: FakeAuthRepository
  private lateinit var viewModel: AuthViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeAuthRepository()
    viewModel = AuthViewModel(repository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun waitsForInitialSessionBeforeAllowingSubmission() = runTest {
    fillLogin()
    viewModel.submit()
    advanceUntilIdle()
    assertTrue(viewModel.state.value.isRestoringSession)
    assertTrue(repository.calls.isEmpty())
    repository.sessions.emit(null)
    advanceUntilIdle()
    assertFalse(viewModel.state.value.isRestoringSession)
  }

  @Test
  fun restoresPersistedSessionWithoutCredentials() = runTest {
    fillLogin()
    repository.sessions.emit(AuthSession("existing-user", "student@example.org"))
    advanceUntilIdle()
    assertEquals("existing-user", viewModel.state.value.session?.uid)
    assertEquals("", viewModel.state.value.password)
    viewModel.submit()
    advanceUntilIdle()
    assertTrue(repository.calls.isEmpty())
  }

  @Test
  fun rejectsBlankOrMalformedEmailWithoutCallingRepository() = runTest {
    ready()
    for (email in listOf("", " ", "student", "student@", "@example.org", "a b@example.org")) {
      fillLogin(email)
      viewModel.submit()
      assertEquals(AuthUiError.INVALID_EMAIL, viewModel.state.value.error)
    }
    assertTrue(repository.calls.isEmpty())
  }

  @Test
  fun requiresPasswordButAllowsShortExistingLoginPassword() = runTest {
    ready()
    fillLogin(password = "")
    viewModel.submit()
    assertEquals(AuthUiError.PASSWORD_REQUIRED, viewModel.state.value.error)
    fillLogin(password = "abc")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(listOf(Attempt(false, "student@example.org", "abc")), repository.calls)
  }

  @Test
  fun trimsEmailButPreservesPasswordSpaces() = runTest {
    ready()
    fillLogin("  student@example.org  ", " secret ")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(Attempt(false, "student@example.org", " secret "), repository.calls.single())
  }

  @Test
  fun signupChecksPasswordThresholdAndConfirmation() = runTest {
    ready()
    viewModel.switchMode()
    for (password in listOf("12345", "123456", "1234567")) {
      fillLogin(password = password)
      viewModel.updateConfirmPassword(password)
      viewModel.submit()
      advanceUntilIdle()
      if (password.length == 5) assertEquals(AuthUiError.WEAK_PASSWORD, viewModel.state.value.error)
      else {
        repository.sessions.emit(AuthSession("new-user", "student@example.org"))
        advanceUntilIdle()
        ready()
      }
    }
    assertEquals(listOf("123456", "1234567"), repository.calls.map { it.password })
    assertTrue(repository.calls.all { it.signup })
    viewModel.updatePassword("1234567")
    viewModel.updateConfirmPassword("different")
    viewModel.submit()
    assertEquals(AuthUiError.PASSWORD_MISMATCH, viewModel.state.value.error)
    assertEquals(2, repository.calls.size)
  }

  @Test
  fun switchingModeClearsPasswordsAndValidationErrors() = runTest {
    ready()
    fillLogin(email = "invalid")
    viewModel.updateConfirmPassword("secret")
    viewModel.submit()
    viewModel.switchMode()
    assertEquals(AuthMode.SIGN_UP, viewModel.state.value.mode)
    assertEquals("", viewModel.state.value.password)
    assertEquals("", viewModel.state.value.confirmPassword)
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun selectModeIsExplicitAndAlwaysClearsOldSecretsAndErrors() = runTest {
    ready()
    viewModel.selectMode(AuthMode.SIGN_UP)
    fillLogin(email = "invalid")
    viewModel.updateConfirmPassword("secret")
    viewModel.submit()
    assertEquals(AuthUiError.INVALID_EMAIL, viewModel.state.value.error)
    viewModel.selectMode(AuthMode.SIGN_UP)
    assertEquals(AuthMode.SIGN_UP, viewModel.state.value.mode)
    assertEquals("", viewModel.state.value.password)
    assertEquals("", viewModel.state.value.confirmPassword)
    assertNull(viewModel.state.value.error)
    viewModel.selectMode(AuthMode.LOGIN)
    assertEquals(AuthMode.LOGIN, viewModel.state.value.mode)
    assertTrue(repository.calls.isEmpty())
  }

  @Test
  fun resetFormClearsSecretsAndErrorsWithoutChangingEmailOrMode() = runTest {
    ready()
    viewModel.selectMode(AuthMode.SIGN_UP)
    fillLogin()
    viewModel.updateConfirmPassword("different")
    viewModel.submit()
    assertEquals(AuthUiError.PASSWORD_MISMATCH, viewModel.state.value.error)
    viewModel.resetForm()
    assertEquals("student@example.org", viewModel.state.value.email)
    assertEquals(AuthMode.SIGN_UP, viewModel.state.value.mode)
    assertEquals("", viewModel.state.value.password)
    assertEquals("", viewModel.state.value.confirmPassword)
    assertNull(viewModel.state.value.error)
    assertNull(viewModel.state.value.session)
    assertTrue(repository.calls.isEmpty())
  }

  @Test
  fun successfulSessionClearsSecretsAndExternalSignoutClosesSession() = runTest {
    ready()
    fillLogin()
    viewModel.updateConfirmPassword("secret")
    viewModel.submit()
    advanceUntilIdle()
    assertNull(viewModel.state.value.session)
    repository.sessions.emit(AuthSession("new-user", "student@example.org"))
    advanceUntilIdle()
    assertEquals("", viewModel.state.value.password)
    assertEquals("", viewModel.state.value.confirmPassword)
    repository.sessions.emit(null)
    advanceUntilIdle()
    assertNull(viewModel.state.value.session)
  }

  @Test
  fun domainFailuresAreSafeAndCanBeRetried() = runTest {
    ready()
    fillLogin()
    for (error in AuthError.values()) {
      repository.failure = AuthException(error)
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(AuthUiError.valueOf(error.name), viewModel.state.value.error)
      assertFalse(viewModel.state.value.isLoading)
      assertNull(viewModel.state.value.session)
    }
    repository.failure = null
    viewModel.submit()
    advanceUntilIdle()
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun unexpectedFailureDoesNotExposeExceptionMessage() = runTest {
    ready()
    fillLogin()
    repository.failure = IllegalStateException("sensitive server response")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(AuthUiError.UNKNOWN, viewModel.state.value.error)
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun pendingRequestCannotBeSubmittedTwiceOrEdited() = runTest {
    ready()
    fillLogin()
    val gate = CompletableDeferred<Unit>()
    repository.gate = gate
    viewModel.submit()
    viewModel.submit()
    advanceUntilIdle()
    assertTrue(viewModel.state.value.isLoading)
    viewModel.switchMode()
    viewModel.selectMode(AuthMode.SIGN_UP)
    viewModel.resetForm()
    viewModel.updateEmail("changed@example.org")
    viewModel.updatePassword("changed")
    viewModel.signOut()
    assertEquals(AuthMode.LOGIN, viewModel.state.value.mode)
    assertEquals("student@example.org", viewModel.state.value.email)
    assertEquals("secret", viewModel.state.value.password)
    assertEquals(1, repository.calls.size)
    assertEquals(0, repository.signOutCalls)
    repository.sessions.emit(AuthSession("user", "student@example.org"))
    advanceUntilIdle()
    assertTrue(viewModel.state.value.isLoading)
    gate.complete(Unit)
    advanceUntilIdle()
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun cancellationDoesNotBecomeAnAuthenticationError() = runTest {
    ready()
    fillLogin()
    repository.failure = kotlinx.coroutines.CancellationException("cancelled")
    viewModel.submit()
    advanceUntilIdle()
    assertNull(viewModel.state.value.error)
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun signingOutClearsSessionAndForm() = runTest {
    repository.sessions.emit(AuthSession("user", "student@example.org"))
    advanceUntilIdle()
    viewModel.updateEmail("student@example.org")
    viewModel.signOut()
    advanceUntilIdle()
    assertEquals(1, repository.signOutCalls)
    assertNull(viewModel.state.value.session)
    assertEquals("", viewModel.state.value.email)
    assertFalse(viewModel.state.value.isRestoringSession)
  }

  @Test
  fun successfulRequestWaitsForSessionBeforeUnlockingForm() = runTest {
    ready()
    for (mode in AuthMode.values()) {
      viewModel.selectMode(mode)
      fillLogin()
      viewModel.updateConfirmPassword("secret")
      val previousCalls = repository.calls.size
      viewModel.submit()
      advanceUntilIdle()
      assertTrue(viewModel.state.value.isLoading)
      assertNull(viewModel.state.value.session)
      viewModel.submit()
      viewModel.updatePassword("changed")
      advanceUntilIdle()
      assertEquals(previousCalls + 1, repository.calls.size)
      assertEquals("secret", viewModel.state.value.password)
      repository.sessions.emit(AuthSession("user", "student@example.org"))
      advanceUntilIdle()
      assertFalse(viewModel.state.value.isLoading)
      assertEquals("user", viewModel.state.value.session?.uid)
      assertEquals("", viewModel.state.value.password)
      assertEquals("", viewModel.state.value.confirmPassword)
      viewModel.signOut()
      advanceUntilIdle()
    }
  }

  private suspend fun ready() {
    repository.sessions.emit(null)
    dispatcher.scheduler.advanceUntilIdle()
  }

  private fun fillLogin(email: String = "student@example.org", password: String = "secret") {
    viewModel.updateEmail(email)
    viewModel.updatePassword(password)
  }

  private data class Attempt(val signup: Boolean, val email: String, val password: String)

  private class FakeAuthRepository : AuthRepository {
    val sessions = MutableSharedFlow<AuthSession?>(replay = 1)
    override val session: Flow<AuthSession?> = sessions
    val calls = mutableListOf<Attempt>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var signOutCalls = 0

    override suspend fun signIn(email: String, password: String) =
        authenticate(false, email, password)

    override suspend fun signUp(email: String, password: String) =
        authenticate(true, email, password)

    private suspend fun authenticate(signup: Boolean, email: String, password: String) {
      calls.add(Attempt(signup, email, password))
      gate?.await()
      failure?.let { throw it }
    }

    override fun signOut() {
      signOutCalls++
      sessions.tryEmit(null)
    }
  }
}
