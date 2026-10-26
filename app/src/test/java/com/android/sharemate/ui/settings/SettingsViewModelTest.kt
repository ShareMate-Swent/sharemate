// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val household =
      Household(id = "home", name = "Flat", inviteCode = "ABC123", memberIds = listOf("user-1"))
  private val googleSession =
      AuthSession(
          uid = "user-1",
          email = "user@example.com",
          displayName = "Inge",
          photoUrl = "https://example.org/photo.png",
          provider = SignInProvider.GOOGLE)
  private lateinit var auth: FakeAuthRepository
  private lateinit var households: RecordingHouseholdRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(googleSession)
    households = RecordingHouseholdRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel() = SettingsViewModel(auth, households, household)

  @Test
  fun showsTheLoadedHouseholdAtOnceAndThenTheProfile() = runTest {
    val viewModel = createViewModel()

    assertEquals(
        SettingsUiState(householdName = "Flat", memberCount = 1, inviteCode = "ABC123"),
        viewModel.state.value)

    advanceUntilIdle()

    assertEquals(
        SettingsUiState(
            email = "user@example.com",
            displayName = "Inge",
            photoUrl = "https://example.org/photo.png",
            provider = SignInProvider.GOOGLE,
            householdName = "Flat",
            memberCount = 1,
            inviteCode = "ABC123"),
        viewModel.state.value)
  }

  @Test
  fun refetchesTheHouseholdSoTheMemberCountIsCurrent() = runTest {
    households.latest = household.copy(name = "New flat", memberIds = listOf("user-1", "user-2"))
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals(listOf("home"), households.requestedIds)
    assertEquals("New flat", viewModel.state.value.householdName)
    assertEquals(2, viewModel.state.value.memberCount)

    households.latest = household.copy(memberIds = listOf("user-1", "user-2", "user-3"))
    viewModel.refreshHousehold()
    advanceUntilIdle()

    assertEquals(3, viewModel.state.value.memberCount)
  }

  @Test
  fun exposesTheFridgeTypeOfTheLoadedAndRefetchedHousehold() = runTest {
    val privateHousehold = household.copy(type = FridgeType.PRIVATE)
    households.latest = privateHousehold
    val viewModel = SettingsViewModel(auth, households, privateHousehold)

    assertEquals(FridgeType.PRIVATE, viewModel.state.value.fridgeType)

    households.latest = household.copy(type = FridgeType.SHARED)
    advanceUntilIdle()

    assertEquals(FridgeType.SHARED, viewModel.state.value.fridgeType)
  }

  @Test
  fun keepsTheLoadedHouseholdWhenTheFetchFailsOrFindsNothing() = runTest {
    households.failure = IllegalStateException("offline")
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals("Flat", viewModel.state.value.householdName)
    assertEquals(1, viewModel.state.value.memberCount)

    households.failure = null
    households.latest = null
    viewModel.refreshHousehold()
    advanceUntilIdle()

    assertEquals("Flat", viewModel.state.value.householdName)
    assertEquals("ABC123", viewModel.state.value.inviteCode)
  }

  @Test
  fun doesNotStartASecondFetchWhileOneIsRunning() = runTest {
    households.gate = CompletableDeferred()
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.refreshHousehold()
    advanceUntilIdle()
    assertEquals(1, households.requestedIds.size)

    households.gate?.complete(Unit)
    advanceUntilIdle()
    viewModel.refreshHousehold()
    advanceUntilIdle()
    assertEquals(2, households.requestedIds.size)
  }

  @Test
  fun followsSessionChangesAndKeepsTheLastProfileAfterSignOut() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    auth.sessionState.value =
        AuthSession("user-1", "other@example.com", provider = SignInProvider.EMAIL)
    advanceUntilIdle()

    assertEquals("other@example.com", viewModel.state.value.email)
    assertEquals(null, viewModel.state.value.displayName)
    assertEquals(null, viewModel.state.value.photoUrl)
    assertEquals(SignInProvider.EMAIL, viewModel.state.value.provider)

    auth.signOut()
    advanceUntilIdle()

    assertEquals("other@example.com", viewModel.state.value.email)
    assertEquals(SignInProvider.EMAIL, viewModel.state.value.provider)
  }

  @Test
  fun switchingSignsInWithTheChosenAccountWithoutSigningOutFirst() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    auth.googleGate = CompletableDeferred()

    viewModel.switchGoogleAccount { "new-token" }
    advanceUntilIdle()

    assertTrue(viewModel.state.value.isSwitchingAccount)
    assertEquals(listOf("new-token"), auth.googleTokens)
    assertEquals("user-1", auth.sessionState.value?.uid)

    auth.googleGate?.complete(Unit)
    advanceUntilIdle()

    assertEquals("user-2", auth.sessionState.value?.uid)
    assertEquals("new@example.com", viewModel.state.value.email)
    assertFalse(viewModel.state.value.isSwitchingAccount)
    assertNull(viewModel.state.value.accountError)
  }

  @Test
  fun cancellingThePickerKeepsTheCurrentAccount() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.switchGoogleAccount { null }
    advanceUntilIdle()

    assertEquals(emptyList<String>(), auth.googleTokens)
    assertEquals(googleSession, auth.sessionState.value)
    assertFalse(viewModel.state.value.isSwitchingAccount)
    assertNull(viewModel.state.value.accountError)
  }

  @Test
  fun pickerFailuresReportGoogleAsUnavailable() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    for (failure in
        listOf(AuthException(AuthError.GOOGLE_UNAVAILABLE), IllegalStateException("no picker"))) {
      viewModel.switchGoogleAccount { throw failure }
      advanceUntilIdle()

      assertEquals(SettingsAccountError.GOOGLE_UNAVAILABLE, viewModel.state.value.accountError)
      assertFalse(viewModel.state.value.isSwitchingAccount)
    }
    assertEquals(emptyList<String>(), auth.googleTokens)
  }

  @Test
  fun signInFailuresKeepTheAccountAndExplainWhy() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    val cases =
        listOf(
            AuthException(AuthError.NETWORK) to SettingsAccountError.NETWORK,
            AuthException(AuthError.ACCOUNT_CONFLICT) to SettingsAccountError.SWITCH_FAILED,
            IllegalStateException("unexpected") to SettingsAccountError.SWITCH_FAILED)

    for ((failure, expected) in cases) {
      auth.googleFailure = failure
      viewModel.switchGoogleAccount { "new-token" }
      advanceUntilIdle()

      assertEquals(expected, viewModel.state.value.accountError)
      assertFalse(viewModel.state.value.isSwitchingAccount)
      assertEquals(googleSession, auth.sessionState.value)
    }
  }

  @Test
  fun blankTokenFailsWithoutSigningIn() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.switchGoogleAccount { " " }
    advanceUntilIdle()

    assertEquals(SettingsAccountError.SWITCH_FAILED, viewModel.state.value.accountError)
    assertEquals(emptyList<String>(), auth.googleTokens)
  }

  @Test
  fun aSecondSwitchIsIgnoredWhileOneRunsAndANewAttemptClearsTheError() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    auth.googleFailure = AuthException(AuthError.NETWORK)
    auth.googleGate = CompletableDeferred()

    viewModel.switchGoogleAccount { "first" }
    advanceUntilIdle()
    viewModel.switchGoogleAccount { "second" }
    advanceUntilIdle()
    assertEquals(listOf("first"), auth.googleTokens)

    auth.googleGate?.complete(Unit)
    advanceUntilIdle()
    assertEquals(SettingsAccountError.NETWORK, viewModel.state.value.accountError)

    auth.googleFailure = null
    auth.googleGate = CompletableDeferred()
    viewModel.switchGoogleAccount { "third" }
    advanceUntilIdle()
    assertNull(viewModel.state.value.accountError)
    assertTrue(viewModel.state.value.isSwitchingAccount)
  }

  private class FakeAuthRepository(session: AuthSession?) : AuthRepository {
    val sessionState = MutableStateFlow(session)
    override val session: Flow<AuthSession?> = sessionState

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    val googleTokens = mutableListOf<String>()
    var googleFailure: Exception? = null
    var googleGate: CompletableDeferred<Unit>? = null

    /** Like Firebase, a successful Google sign-in replaces the current user. */
    override suspend fun signInWithGoogle(idToken: String) {
      googleTokens += idToken
      googleGate?.await()
      googleFailure?.let { throw it }
      sessionState.value =
          AuthSession("user-2", "new@example.com", provider = SignInProvider.GOOGLE)
    }

    override fun signOut() {
      sessionState.value = null
    }
  }

  private inner class RecordingHouseholdRepository :
      HouseholdRepository by InMemoryHouseholdRepository() {
    val requestedIds = mutableListOf<String>()
    var latest: Household? = household
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getHousehold(householdId: String): Household? {
      requestedIds += householdId
      gate?.await()
      failure?.let { throw it }
      return latest
    }
  }
}
