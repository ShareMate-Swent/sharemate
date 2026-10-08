package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HouseholdGateViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()
  private lateinit var repository: LookupRepository
  private lateinit var viewModel: HouseholdGateViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = LookupRepository()
  }

  @After
  fun tearDown() {
    store.clear()
    dispatcher.scheduler.runCurrent()
    Dispatchers.resetMain()
  }

  @Test
  fun waitsForLookupAndReturnsHouseholdForAuthenticatedUser() = runTest {
    val household = Household(id = "household", memberIds = listOf("authenticated-user"))
    val pending = CompletableDeferred<Unit>()
    repository.pending = pending
    repository.result = household
    startViewModel()
    assertEquals(HouseholdGateState.Loading, viewModel.state.value)
    advanceUntilIdle()
    assertEquals(listOf("authenticated-user"), repository.userIds)
    assertEquals(HouseholdGateState.Loading, viewModel.state.value)
    pending.complete(Unit)
    advanceUntilIdle()
    assertEquals(HouseholdGateState.Ready(household), viewModel.state.value)
  }

  @Test
  fun missingHouseholdRequiresOnboarding() = runTest {
    startViewModel()
    advanceUntilIdle()
    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
    assertEquals(listOf("authenticated-user"), repository.userIds)
  }

  @Test
  fun failedLookupCanBeRetriedAndRechecksMembership() = runTest {
    repository.failure = IllegalStateException("Backend unavailable")
    startViewModel()
    advanceUntilIdle()
    assertEquals(HouseholdGateState.Error, viewModel.state.value)
    repository.failure = null
    val household = Household(id = "created-household")
    repository.result = household
    viewModel.refresh()
    assertEquals(HouseholdGateState.Loading, viewModel.state.value)
    advanceUntilIdle()
    assertEquals(HouseholdGateState.Ready(household), viewModel.state.value)
    assertEquals(listOf("authenticated-user", "authenticated-user"), repository.userIds)
  }

  @Test
  fun repeatedRefreshDoesNotDuplicatePendingLookup() = runTest {
    val pending = CompletableDeferred<Unit>()
    repository.pending = pending
    startViewModel()
    viewModel.refresh()
    advanceUntilIdle()
    viewModel.refresh()
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(listOf("authenticated-user"), repository.userIds)
    assertEquals(HouseholdGateState.Loading, viewModel.state.value)
    pending.complete(Unit)
    advanceUntilIdle()
    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
  }

  @Test
  fun clearingViewModelCancelsLookupWithoutErrorOrLateHousehold() = runTest {
    val pending = CompletableDeferred<Unit>()
    repository.pending = pending
    repository.result = Household(id = "late-household")
    startViewModel()
    advanceUntilIdle()
    store.clear()
    pending.complete(Unit)
    advanceUntilIdle()
    assertEquals(HouseholdGateState.Loading, viewModel.state.value)
    assertEquals(listOf("authenticated-user"), repository.userIds)
  }

  private fun startViewModel() {
    viewModel = HouseholdGateViewModel(repository, "authenticated-user")
    store.put("household-gate", viewModel)
  }

  private class LookupRepository : HouseholdRepository by InMemoryHouseholdRepository() {
    val userIds = mutableListOf<String>()
    var result: Household? = null
    var failure: Exception? = null
    var pending: CompletableDeferred<Unit>? = null

    override suspend fun getHouseholdForUser(userId: String): Household? {
      userIds.add(userId)
      pending?.await()
      failure?.let { throw it }
      return result
    }
  }
}
