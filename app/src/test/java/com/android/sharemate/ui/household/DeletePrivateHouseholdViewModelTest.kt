// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.PrivateHouseholdDeletionRepository
import java.io.IOException
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
class DeletePrivateHouseholdViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var deletion: RecordingDeletion
  private lateinit var auth: FakeAuthRepository
  private lateinit var viewModel: DeletePrivateHouseholdViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    deletion = RecordingDeletion()
    auth = FakeAuthRepository(AuthSession("user-1", "user@example.com"))
    viewModel = DeletePrivateHouseholdViewModel(HOUSEHOLD_ID, deletion, auth)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun nothingIsDeletedUntilTheUserConfirms() = runTest {
    viewModel.requestDelete()
    advanceUntilIdle()

    assertTrue(viewModel.state.value.isConfirming)
    assertTrue(deletion.deletedIds.isEmpty())
  }

  @Test
  fun confirmingDeletesThePrivateHouseholdAndReportsThatTheUserLeft() = runTest {
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertEquals(listOf(HOUSEHOLD_ID), deletion.deletedIds)
    assertTrue(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isConfirming)
    assertFalse(viewModel.state.value.isLeaving)
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun cancellingClosesTheDialogWithoutDeleting() = runTest {
    viewModel.requestDelete()
    viewModel.cancel()
    advanceUntilIdle()

    assertFalse(viewModel.state.value.isConfirming)
    assertTrue(deletion.deletedIds.isEmpty())
  }

  @Test
  fun confirmingWithoutAnOpenDialogDoesNothing() = runTest {
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertTrue(deletion.deletedIds.isEmpty())
    assertFalse(viewModel.state.value.left)
  }

  @Test
  fun aFailureKeepsTheDialogOpenAndTheHouseholdNotLeft() = runTest {
    deletion.failure = IOException("offline")
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertEquals(LeaveHouseholdError.DELETE_FAILED, viewModel.state.value.error)
    assertTrue(viewModel.state.value.isConfirming)
    assertFalse(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isLeaving)
  }

  @Test
  fun retryingAfterAFailureAsksTheServerAgainForTheSameHousehold() = runTest {
    deletion.failure = IOException("offline")
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    deletion.failure = null
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertEquals(listOf(HOUSEHOLD_ID, HOUSEHOLD_ID), deletion.deletedIds)
    assertTrue(viewModel.state.value.left)
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun cancellingAfterAFailureClearsTheError() = runTest {
    deletion.failure = IOException("offline")
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    viewModel.cancel()

    assertFalse(viewModel.state.value.isConfirming)
    assertNull(viewModel.state.value.error)
  }

  @Test
  fun withoutASessionNothingIsDeletedAndTheUserIsToldToSignIn() = runTest {
    auth.sessionState.value = null
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertEquals(LeaveHouseholdError.NO_SESSION, viewModel.state.value.error)
    assertTrue(deletion.deletedIds.isEmpty())
    assertFalse(viewModel.state.value.left)
  }

  @Test
  fun whileDeletingTheRequestCannotBeRepeatedOrCancelled() = runTest {
    deletion.gate = CompletableDeferred()
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    viewModel.confirmDelete()
    viewModel.cancel()
    viewModel.requestDelete()
    advanceUntilIdle()

    assertTrue(viewModel.state.value.isLeaving)
    assertTrue(viewModel.state.value.isConfirming)
    assertEquals(1, deletion.deletedIds.size)

    deletion.gate!!.complete(Unit)
    advanceUntilIdle()

    assertTrue(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isLeaving)
  }

  @Test
  fun onceDeletedTheRequestCannotBeRepeated() = runTest {
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertEquals(1, deletion.deletedIds.size)
    assertFalse(viewModel.state.value.isConfirming)
  }

  @Test
  fun cancellationIsNotReportedAsAFailure() = runTest {
    deletion.failure = CancellationException("cancelled")
    viewModel.requestDelete()
    viewModel.confirmDelete()
    advanceUntilIdle()

    assertNull(viewModel.state.value.error)
    assertFalse(viewModel.state.value.left)
    assertFalse(viewModel.state.value.isLeaving)
  }

  private class FakeAuthRepository(session: AuthSession?) : AuthRepository {
    val sessionState = MutableStateFlow(session)
    override val session: Flow<AuthSession?> = sessionState

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

    override fun signOut() {
      sessionState.value = null
    }
  }

  private class RecordingDeletion : PrivateHouseholdDeletionRepository {
    val deletedIds = mutableListOf<String>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun deletePrivateHousehold(householdId: String) {
      deletedIds += householdId
      gate?.await()
      failure?.let { throw it }
    }
  }

  private companion object {
    const val HOUSEHOLD_ID = "user-1:private"
  }
}
