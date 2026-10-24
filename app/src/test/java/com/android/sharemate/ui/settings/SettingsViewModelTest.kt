// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
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

  private class FakeAuthRepository(session: AuthSession?) : AuthRepository {
    val sessionState = MutableStateFlow(session)
    override val session: Flow<AuthSession?> = sessionState

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

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
