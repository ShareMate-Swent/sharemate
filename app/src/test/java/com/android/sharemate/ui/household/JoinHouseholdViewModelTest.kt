// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinHouseholdViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var repository: RecordingRepository
  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = RecordingRepository()
    auth = FakeAuthRepository(AuthSession("user-1", "user@example.com"))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun validCodeIsJoined() = runTest {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    viewModel.updateInviteCode("ab23cd")
    viewModel.joinHousehold()
    advanceUntilIdle()
    assertEquals(listOf("AB23CD" to "user-1"), repository.joined)
    assertTrue(viewModel.state.value.joined)
  }

  @Test
  fun lowercaseIsAcceptedAndLongInputIsTruncated() {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    viewModel.updateInviteCode("ab12cd789")
    assertEquals("AB12CD", viewModel.state.value.inviteCode)
  }

  @Test
  fun punctuationWhitespaceNonAsciiLettersAndDigitsAreRejected() {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    viewModel.updateInviteCode("a-!b 1_2cé١")
    assertEquals("AB12C", viewModel.state.value.inviteCode)
  }

  @Test
  fun invalidCodeShowsSpecificError() = runTest {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    repository.failure = IllegalArgumentException("invalid invite code")
    viewModel.updateInviteCode("ABC234")
    viewModel.joinHousehold()
    advanceUntilIdle()
    assertEquals(JoinHouseholdError.INVALID_CODE, viewModel.state.value.error)
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun otherFailureShowsGenericErrorAndCanBeRetried() = runTest {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    repository.failure = IllegalStateException("unavailable")
    viewModel.updateInviteCode("ABC234")
    viewModel.joinHousehold()
    advanceUntilIdle()
    assertEquals(JoinHouseholdError.JOIN_FAILED, viewModel.state.value.error)
    repository.failure = null
    viewModel.joinHousehold()
    advanceUntilIdle()
    assertTrue(viewModel.state.value.joined)
  }

  @Test
  fun noSessionDoesNotCallRepository() = runTest {
    auth.sessionState.value = null
    val viewModel = JoinHouseholdViewModel(repository, auth)
    viewModel.updateInviteCode("ABC234")
    viewModel.joinHousehold()
    advanceUntilIdle()
    assertEquals(JoinHouseholdError.NO_SESSION, viewModel.state.value.error)
    assertTrue(repository.joined.isEmpty())
  }

  @Test
  fun loadingPreventsDoubleSubmission() = runTest {
    val viewModel = JoinHouseholdViewModel(repository, auth)
    repository.gate = CompletableDeferred()
    viewModel.updateInviteCode("ABC234")
    viewModel.joinHousehold()
    advanceUntilIdle()
    viewModel.joinHousehold()
    assertTrue(viewModel.state.value.isLoading)
    assertEquals(1, repository.joinCalls.get())
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertFalse(viewModel.state.value.isLoading)
  }

  private class FakeAuthRepository(session: AuthSession?) : AuthRepository {
    val sessionState = MutableStateFlow(session)
    override val session: Flow<AuthSession?> = sessionState

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override fun signOut() {
      sessionState.value = null
    }
  }

  private class RecordingRepository : HouseholdRepository {
    private val delegate = InMemoryHouseholdRepository()
    val joined = mutableListOf<Pair<String, String>>()
    val joinCalls = AtomicInteger()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createHousehold(name: String, creatorId: String) =
        delegate.createHousehold(name, creatorId)

    override suspend fun joinHousehold(inviteCode: String, userId: String): Household {
      joinCalls.incrementAndGet()
      joined += inviteCode to userId
      gate?.await()
      failure?.let { throw it }
      return Household(inviteCode = inviteCode, memberIds = listOf(userId))
    }

    override suspend fun getHousehold(householdId: String) = delegate.getHousehold(householdId)

    override suspend fun getHouseholdForUser(userId: String) = delegate.getHouseholdForUser(userId)
  }
}
