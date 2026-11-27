// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryDeletedHouseholdStore
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import com.android.sharemate.model.profile.InMemoryUserProfileRepository
import com.android.sharemate.model.profile.UserProfile
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
  private val profiles = InMemoryUserProfileRepository()
  private val deletedHouseholds = InMemoryDeletedHouseholdStore()
  private val session =
      AuthSession(uid = "authenticated-user", email = "user@example.com", displayName = "Ada")
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
  fun aDeletedHouseholdIsNotShownAgainEvenWhenTheLookupStillReturnsIt() = runTest {
    // The lookup keeps answering with the household, as a cache would after a lost connection.
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()
    assertEquals(HouseholdGateState.Ready(repository.result!!), viewModel.state.value)

    viewModel.householdDeleted("deleted-home")
    advanceUntilIdle()

    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
    assertEquals(listOf("authenticated-user", "authenticated-user"), repository.userIds)
  }

  @Test
  fun aDeletedHouseholdStaysIgnoredOnLaterRefreshes() = runTest {
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()
    viewModel.householdDeleted("deleted-home")
    advanceUntilIdle()

    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
  }

  @Test
  fun aDeletionConfirmedWhileALookupIsPendingStillHidesItsResult() = runTest {
    val pending = CompletableDeferred<Unit>()
    repository.pending = pending
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()

    viewModel.householdDeleted("deleted-home")
    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
  }

  @Test
  fun aDeletionConfirmedBeforeARestartStillHidesTheHouseholdAfterIt() = runTest {
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()
    viewModel.householdDeleted("deleted-home")
    advanceUntilIdle()

    // The app is closed and started again: a new view model, nothing kept in memory. The lookup
    // still returns the household, as Firestore's cache does when the device is offline.
    startViewModel(key = "restarted-gate")
    advanceUntilIdle()

    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
  }

  @Test
  fun aDeletionIsRememberedForItsAccountOnly() = runTest {
    repository.result = Household(id = "shared-home")
    startViewModel()
    viewModel.householdDeleted("shared-home")
    advanceUntilIdle()

    startViewModel(forSession = session.copy(uid = "another-user"), key = "other-gate")
    advanceUntilIdle()

    assertEquals(HouseholdGateState.Ready(repository.result!!), viewModel.state.value)
  }

  @Test
  fun aDeletionThatCannotBeRecordedStillHidesTheHouseholdForTheSession() = runTest {
    deletedHouseholds.writeFailure = java.io.IOException("Disk full")
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()

    viewModel.householdDeleted("deleted-home")
    advanceUntilIdle()

    assertEquals(HouseholdGateState.NeedsHousehold, viewModel.state.value)
  }

  @Test
  fun recordsThatCannotBeReadDoNotBlockTheLookup() = runTest {
    deletedHouseholds.readFailure = java.io.IOException("Disk error")
    repository.result = Household(id = "home")
    startViewModel()
    advanceUntilIdle()

    assertEquals(HouseholdGateState.Ready(repository.result!!), viewModel.state.value)
  }

  @Test
  fun anotherHouseholdIsStillShownAfterADeletion() = runTest {
    repository.result = Household(id = "deleted-home")
    startViewModel()
    advanceUntilIdle()
    viewModel.householdDeleted("deleted-home")
    advanceUntilIdle()

    val joined = Household(id = "joined-home")
    repository.result = joined
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(HouseholdGateState.Ready(joined), viewModel.state.value)
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

  @Test
  fun startSavesThePublicProfileOfTheSession() = runTest {
    startViewModel()
    advanceUntilIdle()
    assertEquals(
        listOf(UserProfile("authenticated-user", "Ada", email = "user@example.com")),
        profiles.saved)
  }

  @Test
  fun failedProfileSaveDoesNotBlockTheHouseholdLookup() = runTest {
    val household = Household(id = "household", memberIds = listOf("authenticated-user"))
    repository.result = household
    profiles.saveFailure = IllegalStateException("Offline")
    startViewModel()
    advanceUntilIdle()
    assertEquals(1, profiles.saved.size)
    assertEquals(HouseholdGateState.Ready(household), viewModel.state.value)
  }

  @Test
  fun refreshDoesNotSaveTheProfileAgain() = runTest {
    startViewModel()
    advanceUntilIdle()
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(1, profiles.saved.size)
  }

  @Test
  fun changedSessionProfileIsSavedOnceAndOtherUsersAreIgnored() = runTest {
    startViewModel()
    val withPhoto = session.copy(photoUrl = "https://photos.example/new.jpg")
    viewModel.updateSession(session)
    viewModel.updateSession(withPhoto)
    viewModel.updateSession(withPhoto)
    viewModel.updateSession(withPhoto.copy(uid = "someone-else", photoUrl = null))
    advanceUntilIdle()
    assertEquals(listOf(null, "https://photos.example/new.jpg"), profiles.saved.map { it.photoUrl })
  }

  private fun startViewModel(
      forSession: AuthSession = session,
      key: String = "household-gate",
  ) {
    viewModel = HouseholdGateViewModel(repository, profiles, deletedHouseholds, forSession)
    store.put(key, viewModel)
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
