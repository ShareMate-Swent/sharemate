// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import com.android.sharemate.model.user.ExpirationChange
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeExpirationPreferencesRepository()

  @Before fun setUp() = Dispatchers.setMain(dispatcher)

  @After fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(
      userId: String = USER,
      store: ExpirationPreferencesRepository = repository,
  ) = NotificationSettingsViewModel(store, userId)

  private val saved =
      ExpirationPreferences(soonDays = 10, soonRepeatDays = 4, expiredRepeatDays = null)

  @Test
  fun loadsTheSavedPreferencesOfTheUser() = runTest {
    val model = viewModel(store = FakeExpirationPreferencesRepository(mapOf(USER to saved)))

    assertFalse(model.state.value.isLoaded)
    advanceUntilIdle()

    assertTrue(model.state.value.isLoaded)
    assertEquals(saved, model.state.value.preferences)
  }

  @Test
  fun showsDefaultsWhenNothingWasSaved() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    assertTrue(model.state.value.isLoaded)
    assertEquals(ExpirationPreferences(), model.state.value.preferences)
  }

  @Test
  fun aRealUserIsRequired() {
    assertThrows(IllegalArgumentException::class.java) { viewModel(userId = " ") }
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
  fun savesOnlyTheChangedValue() = runTest {
    val model = viewModel(store = FakeExpirationPreferencesRepository(mapOf(USER to saved)))
    advanceUntilIdle()

    model.setSoonDays(11)
    advanceUntilIdle()

    assertEquals(saved.copy(soonDays = 11), model.state.value.preferences)
  }

  @Test
  fun clampsOutOfRangeValuesToTheirBoundaries() = runTest {
    val store =
        FakeExpirationPreferencesRepository(
            mapOf(USER to ExpirationPreferences(soonRepeatDays = 5, expiredRepeatDays = 5)))
    val model = viewModel(store = store)
    advanceUntilIdle()

    model.setSoonDays(ExpirationPreferences.SOON_DAYS_RANGE.last + 1)
    model.setSoonRepeatDays(ExpirationPreferences.REPEAT_DAYS_RANGE.first - 1)
    model.setExpiredRepeatDays(ExpirationPreferences.REPEAT_DAYS_RANGE.last + 1)
    advanceUntilIdle()

    assertEquals(
        listOf<ExpirationChange>(
            ExpirationChange.SoonDays(ExpirationPreferences.SOON_DAYS_RANGE.last),
            ExpirationChange.SoonRepeatDays(ExpirationPreferences.REPEAT_DAYS_RANGE.first),
            ExpirationChange.ExpiredRepeatDays(ExpirationPreferences.REPEAT_DAYS_RANGE.last)),
        store.updates.map { it.second })
  }

  @Test
  fun doesNotSaveAnUnchangedValue() = runTest {
    val model = viewModel()
    advanceUntilIdle()

    model.setSoonDays(ExpirationPreferences.DEFAULT_SOON_DAYS)
    model.setSoonRepeatDays(ExpirationPreferences.DEFAULT_REPEAT_DAYS)
    advanceUntilIdle()

    assertTrue(repository.updates.isEmpty())
  }

  @Test
  fun ignoresChangesUntilThePreferencesAreLoaded() = runTest {
    val model = viewModel()
    assertFalse(model.state.value.isLoaded)

    model.setSoonDays(9)
    advanceUntilIdle()

    assertTrue(repository.updates.isEmpty())
    assertEquals(ExpirationPreferences(), model.state.value.preferences)
  }

  @Test
  fun aFailedSaveIsReportedUntilDismissedAndKeepsTheSavedValue() = runTest {
    val model = viewModel()
    advanceUntilIdle()
    repository.saveFailure = IOException("denied")

    model.setSoonDays(9)
    advanceUntilIdle()

    assertTrue(model.state.value.saveFailed)
    assertEquals(ExpirationPreferences(), model.state.value.preferences)
    model.dismissSaveError()
    assertFalse(model.state.value.saveFailed)
  }

  @Test
  fun aNewChangeClearsThePreviousSaveError() = runTest {
    val model = viewModel()
    advanceUntilIdle()
    repository.saveFailure = IOException("denied")
    model.setSoonDays(9)
    advanceUntilIdle()
    repository.saveFailure = null

    model.setSoonDays(8)
    advanceUntilIdle()

    assertFalse(model.state.value.saveFailed)
    assertEquals(8, model.state.value.preferences.soonDays)
  }

  @Test
  fun aFailedReadKeepsTheControlsHiddenUntilRetrySucceeds() = runTest {
    repository.readFailure = IOException("offline")
    val model = viewModel()
    advanceUntilIdle()

    assertTrue(model.state.value.loadFailed)
    assertFalse(model.state.value.isLoaded)
    model.setSoonDays(9)
    advanceUntilIdle()
    assertTrue(repository.updates.isEmpty())

    repository.readFailure = null
    model.retryLoad()
    advanceUntilIdle()

    assertFalse(model.state.value.loadFailed)
    assertTrue(model.state.value.isLoaded)
  }

  @Test
  fun retryDoesNothingWhenNothingFailed() = runTest {
    val store = CountingRepository()
    val model = viewModel(store = store)
    advanceUntilIdle()

    model.retryLoad()
    advanceUntilIdle()

    assertEquals(1, store.observations)
  }

  @Test
  fun retryRestartsTheListenerAfterItFailsFollowingASuccessfulLoad() = runTest {
    val store = CountingRepository(failAfterFirstSnapshotOnce = true)
    val model = viewModel(store = store)
    advanceUntilIdle()

    assertTrue(model.state.value.isLoaded)
    assertTrue(model.state.value.loadFailed)

    model.retryLoad()
    advanceUntilIdle()

    assertFalse(model.state.value.loadFailed)
    assertEquals(2, store.observations)
  }

  @Test
  fun aChangeIsShownAsSoonAsTheStoreReportsItBeforeTheServerAnswers() = runTest {
    val store = LocalFirstRepository()
    val model = viewModel(store = store)
    advanceUntilIdle()

    model.setSoonDays(8)
    advanceUntilIdle()

    assertEquals(8, model.state.value.preferences.soonDays)
    assertFalse(model.state.value.saveFailed)
  }

  /** The 7, 8, 9, 8 sequence of the review: the last change stays, whatever the server answers. */
  @Test
  fun quickChangesLeaveTheLastOneShownWhileTheServerIsStillAnswering() = runTest {
    val store = LocalFirstRepository(initial = ExpirationPreferences(soonDays = 7))
    val model = viewModel(store = store)
    advanceUntilIdle()

    listOf(8, 9, 8).forEach {
      model.setSoonDays(it)
      advanceUntilIdle()
    }
    assertEquals(8, model.state.value.preferences.soonDays)

    store.answerAll()
    advanceUntilIdle()

    assertEquals(8, model.state.value.preferences.soonDays)
    assertEquals(listOf(8, 9, 8), store.written.map { (it as ExpirationChange.SoonDays).days })
    assertFalse(model.state.value.saveFailed)
  }

  /**
   * The case of the review: the last save is confirmed before the earlier ones. A confirmation
   * never changes what is shown, so the last change stays, and the earlier value never comes back.
   */
  @Test
  fun theLastChangeStaysShownWhenItsSaveIsConfirmedBeforeTheEarlierOnes() = runTest {
    val store = LocalFirstRepository(initial = ExpirationPreferences(soonDays = 7))
    val model = viewModel(store = store)
    advanceUntilIdle()
    listOf(8, 9, 8).forEach {
      model.setSoonDays(it)
      advanceUntilIdle()
    }
    val shownSinceTheLastChange = mutableListOf<Int>()
    backgroundScope.launch(UnconfinedTestDispatcher(dispatcher.scheduler)) {
      model.state.collect { shownSinceTheLastChange += it.preferences.soonDays }
    }
    advanceUntilIdle()

    listOf(2, 0, 1).forEach {
      store.answer(it)
      advanceUntilIdle()
      assertEquals(8, model.state.value.preferences.soonDays)
    }

    assertEquals(listOf(8), shownSinceTheLastChange.distinct())
    assertFalse(model.state.value.saveFailed)
  }

  @Test
  fun aRefusedChangeShowsThePreviousValueAgainAndReportsTheError() = runTest {
    val store = LocalFirstRepository(initial = ExpirationPreferences(soonDays = 7))
    val model = viewModel(store = store)
    advanceUntilIdle()
    model.setSoonDays(8)
    advanceUntilIdle()
    assertEquals(8, model.state.value.preferences.soonDays)

    store.refuseAll()
    advanceUntilIdle()

    assertEquals(7, model.state.value.preferences.soonDays)
    assertTrue(model.state.value.saveFailed)
  }

  /** Counts how often the preferences are observed, and can fail once after a first snapshot. */
  private class CountingRepository(private val failAfterFirstSnapshotOnce: Boolean = false) :
      ExpirationPreferencesRepository {
    var observations = 0
      private set

    override fun observe(userId: String): Flow<ExpirationPreferences> = flow {
      observations++
      emit(ExpirationPreferences())
      if (failAfterFirstSnapshotOnce && observations == 1) throw IOException("listener failed")
    }

    override suspend fun get(userId: String) = ExpirationPreferences()

    override suspend fun save(userId: String, preferences: ExpirationPreferences) = Unit

    override suspend fun update(userId: String, change: ExpirationChange) = Unit
  }

  /**
   * Behaves like Firestore: a write is reported to the listener at once, then the server confirms
   * it ([answer], [answerAll]) or refuses it ([refuseAll]), in which case the previous value is
   * reported again. Each write can be answered on its own, in any order.
   */
  private class LocalFirstRepository(initial: ExpirationPreferences = ExpirationPreferences()) :
      ExpirationPreferencesRepository {
    private val snapshots = MutableStateFlow(initial)
    private val answers = mutableListOf<CompletableDeferred<Boolean>>()

    /** The changes written so far, in order. */
    val written = mutableListOf<ExpirationChange>()

    /** Confirms the write number [index] (0 is the first one) and no other. */
    fun answer(index: Int) = answers[index].complete(true)

    fun answerAll() = answers.forEach { it.complete(true) }

    fun refuseAll() = answers.forEach { it.complete(false) }

    override fun observe(userId: String): Flow<ExpirationPreferences> = snapshots

    override suspend fun get(userId: String) = snapshots.value

    override suspend fun save(userId: String, preferences: ExpirationPreferences) = Unit

    override suspend fun update(userId: String, change: ExpirationChange) {
      val before = snapshots.value
      val answer = CompletableDeferred<Boolean>()
      answers += answer
      written += change
      snapshots.value = change.applyTo(before)
      if (!answer.await()) {
        snapshots.value = before
        throw IOException("refused by the rules")
      }
    }
  }

  private companion object {
    const val USER = "alice"
  }
}
