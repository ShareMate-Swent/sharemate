// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
class CreateHouseholdViewModelTest {
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
  fun successTrimsNameAndShowsInviteCode() = runTest {
    val viewModel = CreateHouseholdViewModel(repository, auth)
    viewModel.updateName("  Shared kitchen  ")
    viewModel.createHousehold()
    advanceUntilIdle()
    assertEquals(listOf("Shared kitchen"), repository.createdNames)
    assertEquals("ABC123", viewModel.state.value.inviteCode)
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun blankNameDoesNotCallRepository() = runTest {
    val viewModel = CreateHouseholdViewModel(repository, auth)
    viewModel.updateName("   ")
    viewModel.createHousehold()
    advanceUntilIdle()
    assertTrue(repository.createdNames.isEmpty())
  }

  @Test
  fun failureCanBeRetried() = runTest {
    val viewModel = CreateHouseholdViewModel(repository, auth)
    repository.failure = IllegalStateException("unavailable")
    viewModel.updateName("Kitchen")
    viewModel.createHousehold()
    advanceUntilIdle()
    assertEquals(CreateHouseholdError.CREATION_FAILED, viewModel.state.value.error)
    repository.failure = null
    viewModel.createHousehold()
    advanceUntilIdle()
    assertEquals("ABC123", viewModel.state.value.inviteCode)
    assertEquals(2, repository.createCalls.get())
  }

  @Test
  fun cancellationIsNotReportedAsCreationFailure() = runTest {
    val viewModel = CreateHouseholdViewModel(repository, auth)
    repository.failure = CancellationException("cancelled")
    viewModel.updateName("Kitchen")
    viewModel.createHousehold()
    advanceUntilIdle()
    assertEquals(1, repository.createCalls.get())
    assertNull(viewModel.state.value.error)
    assertNull(viewModel.state.value.inviteCode)
    assertFalse(viewModel.state.value.isLoading)
  }

  @Test
  fun noSessionDoesNotCallRepository() = runTest {
    auth.sessionState.value = null
    val viewModel = CreateHouseholdViewModel(repository, auth)
    viewModel.updateName("Kitchen")
    viewModel.createHousehold()
    advanceUntilIdle()
    assertEquals(CreateHouseholdError.NO_SESSION, viewModel.state.value.error)
    assertTrue(repository.createdNames.isEmpty())
  }

  @Test
  fun loadingPreventsDoubleSubmission() = runTest {
    val viewModel = CreateHouseholdViewModel(repository, auth)
    repository.gate = CompletableDeferred()
    viewModel.updateName("Kitchen")
    viewModel.createHousehold()
    advanceUntilIdle()
    viewModel.createHousehold()
    assertTrue(viewModel.state.value.isLoading)
    assertEquals(1, repository.createCalls.get())
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
    val createdNames = mutableListOf<String>()
    val createCalls = AtomicInteger()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createHousehold(name: String, creatorId: String): Household {
      createCalls.incrementAndGet()
      createdNames += name
      gate?.await()
      failure?.let { throw it }
      return Household(inviteCode = "ABC123", name = name, createdBy = creatorId)
    }

    override suspend fun joinHousehold(inviteCode: String, userId: String) =
        delegate.joinHousehold(inviteCode, userId)

    override suspend fun getHousehold(householdId: String) = delegate.getHousehold(householdId)

    override suspend fun getHouseholdForUser(userId: String) = delegate.getHouseholdForUser(userId)
  }
}
