// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoonThresholdViewModelTest {
  private val repository = FakeExpirationPreferencesRepository()

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(userId: String = USER) =
      SoonThresholdViewModel(ExpirationPreferencesProvider(repository), userId)

  private fun TestScope.observed(vm: SoonThresholdViewModel): Long {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.soonThresholdDays.collect {}
    }
    runCurrent()
    return vm.soonThresholdDays.value
  }

  @Test
  fun thresholdIsTheDefaultWhenNothingWasSaved() = runTest {
    assertEquals(ExpirationDates.SOON_THRESHOLD_DAYS, observed(viewModel()))
  }

  @Test
  fun thresholdFollowsTheUsersSavedWindow() = runTest {
    repository.save(USER, ExpirationPreferences(soonDays = 21))

    assertEquals(21L, observed(viewModel()))
  }

  @Test
  fun thresholdIgnoresAnotherUsersWindow() = runTest {
    repository.save("someone-else", ExpirationPreferences(soonDays = 40))

    assertEquals(ExpirationPreferences.DEFAULT_SOON_DAYS.toLong(), observed(viewModel()))
  }

  @Test
  fun thresholdUpdatesWhenThePreferenceChanges() = runTest {
    val vm = viewModel()
    observed(vm)

    repository.save(USER, ExpirationPreferences(soonDays = 45))
    runCurrent()

    assertEquals(45L, vm.soonThresholdDays.value)
  }

  @Test
  fun thresholdKeepsTheBoundariesOfTheAllowedRange() = runTest {
    repository.save(
        USER, ExpirationPreferences(soonDays = ExpirationPreferences.SOON_DAYS_RANGE.last))

    assertEquals(ExpirationPreferences.SOON_DAYS_RANGE.last.toLong(), observed(viewModel()))
  }

  @Test
  fun thresholdFallsBackToTheDefaultWhenPreferencesCannotBeRead() = runTest {
    repository.readFailure = IOException("offline")

    assertEquals(ExpirationDates.SOON_THRESHOLD_DAYS, observed(viewModel()))
  }

  @Test
  fun aRealUserIsRequired() {
    assertThrows(IllegalArgumentException::class.java) { viewModel(userId = " ") }
  }

  private companion object {
    const val USER = "alice"
  }
}
