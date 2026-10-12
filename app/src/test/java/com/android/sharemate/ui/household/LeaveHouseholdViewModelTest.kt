// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
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
class LeaveHouseholdViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var repository: RecordingRepository
  private lateinit var auth: FakeAuthRepository
  private lateinit var viewModel: LeaveHouseholdViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = RecordingRepository()
    auth = FakeAuthRepository(AuthSession("user-1", "user@example.com"))
    viewModel = LeaveHouseholdViewModel(repository, auth)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun confirmingLeavesTheHouseholdOfTheSignedInUser() = runTest {
    viewModel.requestLeave()
    assertTrue(viewModel.state.value.isConfirming)

    viewModel.confirmLeave()
    advanceUntilIdle()

    assertEquals(listOf("user-1"), repository.leftUserIds)
    assertTrue(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isConfirming)
    assertFalse(viewModel.state.value.isLeaving)
  }

  @Test
  fun cancellingClosesTheDialogWithoutLeaving() = runTest {
    viewModel.requestLeave()
    viewModel.cancel()
    advanceUntilIdle()

    assertFalse(viewModel.state.value.isConfirming)
    assertTrue(repository.leftUserIds.isEmpty())
  }

  @Test
  fun confirmingWithoutAnOpenDialogDoesNothing() = runTest {
    viewModel.confirmLeave()
    advanceUntilIdle()

    assertTrue(repository.leftUserIds.isEmpty())
    assertFalse(viewModel.state.value.left)
  }

  @Test
  fun failureKeepsTheDialogOpenAndCanBeRetried() = runTest {
    repository.failure = IllegalStateException("unavailable")
    viewModel.requestLeave()
    viewModel.confirmLeave()
    advanceUntilIdle()

    assertEquals(LeaveHouseholdError.LEAVE_FAILED, viewModel.state.value.error)
    assertTrue(viewModel.state.value.isConfirming)
    assertFalse(viewModel.state.value.left)

    repository.failure = null
    viewModel.confirmLeave()
    advanceUntilIdle()

    assertTrue(viewModel.state.value.left)
    assertNull(viewModel.state.value.error)
    assertEquals(2, repository.leftUserIds.size)
  }

  @Test
  fun cancellingAfterAFailureClearsTheError() = runTest {
    repository.failure = IllegalStateException("unavailable")
    viewModel.requestLeave()
    viewModel.confirmLeave()
    advanceUntilIdle()

    viewModel.cancel()

    assertFalse(viewModel.state.value.isConfirming)
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun noSessionDoesNotCallTheRepository() = runTest {
    auth.sessionState.value = null
    viewModel.requestLeave()
    viewModel.confirmLeave()
    advanceUntilIdle()

    assertEquals(LeaveHouseholdError.NO_SESSION, viewModel.state.value.error)
    assertTrue(repository.leftUserIds.isEmpty())
  }

  @Test
  fun whileLeavingTheRequestCannotBeRepeatedOrCancelled() = runTest {
    repository.gate = CompletableDeferred()
    viewModel.requestLeave()
    viewModel.confirmLeave()
    advanceUntilIdle()

    viewModel.confirmLeave()
    viewModel.cancel()
    advanceUntilIdle()

    assertTrue(viewModel.state.value.isLeaving)
    assertTrue(viewModel.state.value.isConfirming)
    assertEquals(1, repository.leftUserIds.size)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()

    assertTrue(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isLeaving)
  }

  @Test
  fun cancellationIsNotReportedAsAFailure() = runTest {
    repository.failure = CancellationException("cancelled")
    viewModel.requestLeave()
    viewModel.confirmLeave()
    advanceUntilIdle()

    assertNull(viewModel.state.value.error)
    assertFalse(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isLeaving)
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

  private class RecordingRepository : HouseholdRepository by InMemoryHouseholdRepository() {
    val leftUserIds = mutableListOf<String>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun leaveHousehold(userId: String) {
      leftUserIds += userId
      gate?.await()
      failure?.let { throw it }
    }
  }
}
