// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        ExpirationPreferences(soonDays = 7, soonRepeatDays = 2, expiredRepeatDays = null),
        repository.saved.last().second)
    assertEquals("bob", repository.saved.last().first)
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

    assertTrue(repository.saved.isEmpty())
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
}
