// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserExpirationFields
import com.android.sharemate.model.user.ExpirationChange
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks, against the Firestore emulator and its rules, what the notification settings screen
 * relies on: the real listener reports a local write at once and in order, even without a
 * connection, and reports the previous value again when a write is refused.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsEmulatorTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = FirebaseExpirationPreferencesRepository(firestore)

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() {
    // The client is shared by every test, so it must be back online whatever happened.
    Tasks.await(firestore.enableNetwork())
    FirebaseEmulator.clear()
  }

  @Test
  fun offlineQuickChangesAreReportedInOrderAndTheLastOneWinsOnceBackOnline() =
      runBlocking<Unit> {
        val userId = FirebaseEmulator.signInAs("alice")
        repository.save(userId, ExpirationPreferences(soonDays = INITIAL_DAYS))
        val reported = CopyOnWriteArrayList<Int>()
        val listener = launch { repository.observe(userId).collect { reported += it.soonDays } }
        awaitUntil { reported.lastOrNull() == INITIAL_DAYS }

        Tasks.await(firestore.disableNetwork())
        // Started without waiting for the server, which cannot answer: only the order is kept.
        val saves =
            QUICK_CHANGES.map {
              async(start = CoroutineStart.UNDISPATCHED) {
                repository.update(userId, ExpirationChange.SoonDays(it))
              }
            }
        awaitQuiet(reported)

        assertEquals(QUICK_CHANGES.last(), reported.last())
        assertTrue("reported $reported", isInOrder(reported, listOf(INITIAL_DAYS) + QUICK_CHANGES))

        Tasks.await(firestore.enableNetwork())
        withTimeout(TIMEOUT_MILLIS) { saves.awaitAll() }
        awaitQuiet(reported)

        assertEquals(QUICK_CHANGES.last(), repository.get(userId).soonDays)
        assertEquals(QUICK_CHANGES.last(), reported.last())
        assertTrue("reported $reported", isInOrder(reported, listOf(INITIAL_DAYS) + QUICK_CHANGES))
        listener.cancel()
      }

  @Test
  fun aWriteRefusedByTheRulesIsFollowedByTheReportOfThePreviousValue() =
      runBlocking<Unit> {
        val userId = FirebaseEmulator.signInAs("alice")
        repository.save(userId, ExpirationPreferences(soonDays = INITIAL_DAYS))
        val reported = CopyOnWriteArrayList<Int>()
        val listener = launch { repository.observe(userId).collect { reported += it.soonDays } }
        awaitUntil { reported.lastOrNull() == INITIAL_DAYS }

        val refusal =
            runCatching {
                  Tasks.await(
                      firestore
                          .collection(FirestoreCollections.USERS)
                          .document(userId)
                          .set(
                              mapOf(
                                  UserExpirationFields.PREFERENCES to
                                      mapOf(UserExpirationFields.SOON_DAYS to OUT_OF_RANGE_DAYS)),
                              SetOptions.merge()))
                }
                .exceptionOrNull()
        awaitQuiet(reported)

        assertTrue("the write was not refused", refusal is ExecutionException)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (refusal?.cause as? FirebaseFirestoreException)?.code)
        assertEquals(INITIAL_DAYS, reported.last())
        // The refused value may be reported for an instant, brought back into range, never kept.
        assertTrue("reported $reported", reported.all { it == INITIAL_DAYS || it == MAX_SOON_DAYS })
        listener.cancel()
      }

  @Test
  fun theViewModelFollowsQuickChangesMadeWithoutAConnection() =
      runBlocking<Unit> {
        val userId = FirebaseEmulator.signInAs("alice")
        val model = NotificationSettingsViewModel(repository, userId)
        awaitUntil { model.state.value.isLoaded }
        Tasks.await(firestore.disableNetwork())

        QUICK_CHANGES.forEach {
          model.setSoonDays(it)
          awaitUntil { model.state.value.preferences.soonDays == it }
        }

        assertEquals(QUICK_CHANGES.last(), model.state.value.preferences.soonDays)
        assertFalse(model.state.value.saveFailed)

        Tasks.await(firestore.enableNetwork())
        awaitUntil { repository.get(userId).soonDays == QUICK_CHANGES.last() }
        delay(QUIET_MILLIS)

        assertEquals(QUICK_CHANGES.last(), model.state.value.preferences.soonDays)
        assertFalse(model.state.value.saveFailed)
      }

  /** Whether [reported], without its repeats, only goes through [expected] in its order. */
  private fun isInOrder(reported: List<Int>, expected: List<Int>): Boolean {
    var next = 0
    for (value in expected) if (next < reported.size && reported[next] == value) next++
    return next == reported.size
  }

  /** Waits until [condition] holds, so a test fails instead of hanging if it never does. */
  private suspend fun awaitUntil(condition: suspend () -> Boolean) {
    withTimeout(TIMEOUT_MILLIS) { while (!condition()) delay(POLL_MILLIS) }
  }

  /** Waits until nothing was added to [values] for [QUIET_MILLIS], so the reports are complete. */
  private suspend fun awaitQuiet(values: List<Int>) {
    var size = values.size
    var quietFor = 0L
    withTimeout(TIMEOUT_MILLIS) {
      while (quietFor < QUIET_MILLIS) {
        delay(POLL_MILLIS)
        if (values.size == size) quietFor += POLL_MILLIS
        else {
          size = values.size
          quietFor = 0L
        }
      }
    }
  }

  private companion object {
    /** The saved window before the quick changes. */
    const val INITIAL_DAYS = 7

    /** The quick 8, 9, 8 sequence of the review. */
    val QUICK_CHANGES = listOf(8, 9, 8)

    /** A window the rules refuse. */
    const val OUT_OF_RANGE_DAYS = 1000

    /** What a refused window becomes once brought back into range. */
    val MAX_SOON_DAYS = ExpirationPreferences.SOON_DAYS_RANGE.last

    const val TIMEOUT_MILLIS = 15_000L
    const val POLL_MILLIS = 50L
    const val QUIET_MILLIS = 500L
  }
}
