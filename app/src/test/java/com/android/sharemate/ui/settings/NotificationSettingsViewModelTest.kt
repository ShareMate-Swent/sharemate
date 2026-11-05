// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import com.android.sharemate.model.user.ExpirationChange
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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
class NotificationSettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeExpirationPreferencesRepository()

  @Before fun setUp() = Dispatchers.setMain(dispatcher)

  @After fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(userId: String = "alice") =
      NotificationSettingsViewModel(repository, userId)

  @Test
  fun loadsTheSavedPreferencesOfTheUser() = runTest {
    val saved = ExpirationPreferences(soonDays = 10, soonRepeatDays = 4, expiredRepeatDays = null)
    val repo = FakeExpirationPreferencesRepository(mapOf("alice" to saved))
    val model = NotificationSettingsViewModel(repo, "alice")

    assertFalse(model.state.value.isLoaded)
    advanceUntilIdle()

    assertTrue(model.state.value.isLoaded)
    assertEquals(saved, model.state.value.preferences)
  }

  @Test
  fun showsDefaultsWhenNothingWasSaved() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    assertEquals(ExpirationPreferences(), model.state.value.preferences)
  }

  @Test
  fun savesEveryChangeForTheSignedInUser() = runTest {
    val model = viewModel("bob")
    advanceUntilIdle()

    model.setSoonDays(7)
    model.setSoonRepeatDays(2)
    model.setExpiredRepeatDays(null)
    advanceUntilIdle()

    assertEquals(
        listOf<Pair<String, ExpirationChange>>(
            "bob" to ExpirationChange.SoonDays(7),
            "bob" to ExpirationChange.SoonRepeatDays(2),
            "bob" to ExpirationChange.ExpiredRepeatDays(null)),
        repository.updates)
  }

  @Test
  fun clampsOutOfRangeValues() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    model.setSoonDays(61)
    model.setExpiredRepeatDays(0)
    advanceUntilIdle()

    assertEquals(60, model.state.value.preferences.soonDays)
    assertEquals(1, model.state.value.preferences.expiredRepeatDays)
  }

  @Test
  fun doesNotSaveAnUnchangedValue() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    model.setSoonDays(ExpirationPreferences.DEFAULT_SOON_DAYS)
    advanceUntilIdle()

    assertTrue(repository.updates.isEmpty())
  }

  @Test
  fun reportsAFailedSaveUntilDismissed() = runTest {
    repository.saveFailure = IllegalStateException("offline")
    val model = viewModel()
    advanceUntilIdle()

    model.setSoonDays(9)
    advanceUntilIdle()
    assertTrue(model.state.value.saveFailed)

    model.dismissSaveError()
    assertFalse(model.state.value.saveFailed)
  }

  @Test
  fun failedReadKeepsControlsHiddenUntilRetrySucceeds() = runTest {
    val flaky =
        object : ExpirationPreferencesRepository {
          var failing = true

          override fun observe(userId: String): Flow<ExpirationPreferences> = flow {
            if (failing) throw IllegalStateException("offline")
            emit(ExpirationPreferences(soonDays = 5))
          }

          override suspend fun get(userId: String) = ExpirationPreferences()

          override suspend fun save(userId: String, preferences: ExpirationPreferences) = Unit

          override suspend fun update(userId: String, change: ExpirationChange) = Unit
        }
    val model = NotificationSettingsViewModel(flaky, "alice")
    advanceUntilIdle()

    assertTrue(model.state.value.loadFailed)
    assertFalse(model.state.value.isLoaded)

    model.setSoonDays(9)
    advanceUntilIdle()
    assertEquals(ExpirationPreferences(), model.state.value.preferences)

    flaky.failing = false
    model.retryLoad()
    advanceUntilIdle()

    assertFalse(model.state.value.loadFailed)
    assertTrue(model.state.value.isLoaded)
    assertEquals(5, model.state.value.preferences.soonDays)
  }

  @Test
  fun savesOnlyTheChangedValue() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    model.setSoonRepeatDays(4)
    advanceUntilIdle()

    assertEquals(listOf("alice" to ExpirationChange.SoonRepeatDays(4)), repository.updates)
  }

  @Test
  fun staleSnapshotDoesNotUndoANewerEdit() = runTest {
    val snapshots = MutableSharedFlow<ExpirationPreferences>()
    val updates = mutableListOf<ExpirationChange>()
    val slow =
        object : ExpirationPreferencesRepository {
          override fun observe(userId: String): Flow<ExpirationPreferences> = snapshots

          override suspend fun get(userId: String) = ExpirationPreferences()

          override suspend fun save(userId: String, preferences: ExpirationPreferences) = Unit

          override suspend fun update(userId: String, change: ExpirationChange) {
            updates += change
          }
        }
    val model = NotificationSettingsViewModel(slow, "alice")
    advanceUntilIdle()
    snapshots.emit(ExpirationPreferences())
    advanceUntilIdle()

    model.setSoonDays(10)
    model.setSoonRepeatDays(5)
    advanceUntilIdle()
    snapshots.emit(ExpirationPreferences(soonDays = 10))
    advanceUntilIdle()

    model.setExpiredRepeatDays(7)
    advanceUntilIdle()

    assertEquals(
        ExpirationPreferences(soonDays = 10, soonRepeatDays = 5, expiredRepeatDays = 7),
        model.state.value.preferences)
    assertEquals(
        listOf(
            ExpirationChange.SoonDays(10),
            ExpirationChange.SoonRepeatDays(5),
            ExpirationChange.ExpiredRepeatDays(7)),
        updates)
  }

  @Test
  fun failedSaveRevertsToTheSavedValue() = runTest {
    val model = viewModel()
    advanceUntilIdle()
    repository.saveFailure = IllegalStateException("offline")

    model.setSoonDays(9)
    advanceUntilIdle()

    assertEquals(ExpirationPreferences.DEFAULT_SOON_DAYS, model.state.value.preferences.soonDays)
  }
}
