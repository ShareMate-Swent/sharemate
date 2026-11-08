// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserExpirationFields
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The Firestore classes are stubbed by method name rather than with argument matchers, so the tests
 * read as "what the user document contains" and "what the listener is told".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseExpirationPreferencesRepositoryTest {
  private var storedPreferences: Any? = null
  private var listener: EventListener<DocumentSnapshot>? = null
  private var removedListeners = 0
  private val writes = mutableListOf<Pair<Any?, Any?>>()

  private val registration =
      mock(ListenerRegistration::class.java) {
        if (it.method.name == "remove") removedListeners++
        null
      }

  private fun snapshot(stored: Any?): DocumentSnapshot =
      mock(DocumentSnapshot::class.java) {
        if (it.method.name == "get" &&
            it.arguments.singleOrNull() == UserExpirationFields.PREFERENCES)
            stored
        else null
      }

  private val document =
      mock(DocumentReference::class.java) {
        when {
          it.method.name == "get" && it.arguments.isEmpty() ->
              Tasks.forResult(snapshot(storedPreferences))
          it.method.name == "set" -> {
            writes += it.arguments[0] to it.arguments[1]
            Tasks.forResult<Void>(null)
          }
          it.method.name == "addSnapshotListener" && it.arguments.size == 1 -> {
            @Suppress("UNCHECKED_CAST")
            listener = it.arguments[0] as EventListener<DocumentSnapshot>
            registration
          }
          else -> null
        }
      }

  private val users =
      mock(CollectionReference::class.java) {
        if (it.method.name == "document" && it.arguments.single() == USER) document else null
      }

  private val firestore =
      mock(FirebaseFirestore::class.java) {
        if (it.method.name == "collection" && it.arguments.single() == FirestoreCollections.USERS)
            users
        else null
      }

  private val repository = FirebaseExpirationPreferencesRepository(firestore)

  private fun map(soon: Any? = 7, soonRepeat: Any? = 2, expiredRepeat: Any? = 5) =
      buildMap<String, Any?> {
        soon?.let { put(UserExpirationFields.SOON_DAYS, it) }
        soonRepeat?.let { put(UserExpirationFields.SOON_REPEAT_DAYS, it) }
        expiredRepeat?.let { put(UserExpirationFields.EXPIRED_REPEAT_DAYS, it) }
      }

  @Test
  fun userWithoutSavedPreferencesGetsTheDefaults() = runTest {
    assertEquals(ExpirationPreferences(), repository.get(USER))
  }

  @Test
  fun savedValuesAreReadBack() = runTest {
    storedPreferences = map()

    assertEquals(ExpirationPreferences(7, 2, 5), repository.get(USER))
  }

  @Test
  fun zeroDaysMeansNeverAndNegativeIsTreatedTheSame() = runTest {
    storedPreferences = map(soonRepeat = UserExpirationFields.NEVER, expiredRepeat = -4)

    val read = repository.get(USER)

    assertNull(read.soonRepeatDays)
    assertNull(read.expiredRepeatDays)
  }

  @Test
  fun missingOrMalformedFieldsFallBackToTheDefaultsIndividually() = runTest {
    storedPreferences = map(soon = "ten", soonRepeat = null, expiredRepeat = 3L)

    val read = repository.get(USER)

    assertEquals(ExpirationPreferences.DEFAULT_SOON_DAYS, read.soonDays)
    assertEquals(ExpirationPreferences.DEFAULT_REPEAT_DAYS, read.soonRepeatDays)
    assertEquals(3, read.expiredRepeatDays)
  }

  @Test
  fun valuesOutsideTheAllowedRangeAreClamped() = runTest {
    storedPreferences = map(soon = 500, soonRepeat = 99, expiredRepeat = 1)

    assertEquals(
        ExpirationPreferences(
            ExpirationPreferences.SOON_DAYS_RANGE.last,
            ExpirationPreferences.REPEAT_DAYS_RANGE.last,
            1),
        repository.get(USER))
  }

  @Test
  fun aPreferencesFieldThatIsNotAMapIsIgnored() = runTest {
    storedPreferences = "corrupted"

    assertEquals(ExpirationPreferences(), repository.get(USER))
  }

  @Test
  fun saveWritesASanitizedMapIntoTheUserDocumentWithoutErasingOtherFields() = runTest {
    repository.save(
        USER, ExpirationPreferences(soonDays = 90, soonRepeatDays = null, expiredRepeatDays = 0))

    val (data, options) = writes.single()
    assertEquals(SetOptions.merge(), options)
    assertEquals(
        mapOf(
            UserExpirationFields.PREFERENCES to
                mapOf(
                    UserExpirationFields.SOON_DAYS to ExpirationPreferences.SOON_DAYS_RANGE.last,
                    UserExpirationFields.SOON_REPEAT_DAYS to UserExpirationFields.NEVER,
                    UserExpirationFields.EXPIRED_REPEAT_DAYS to
                        ExpirationPreferences.REPEAT_DAYS_RANGE.first)),
        data)
  }

  @Test
  fun observeEmitsEverySnapshotButSkipsRepeatedValues() = runTest {
    val emitted = mutableListOf<ExpirationPreferences>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observe(USER).collect { emitted += it }
    }

    listener!!.onEvent(snapshot(map(soon = 7)), null)
    listener!!.onEvent(snapshot(map(soon = 7)), null)
    listener!!.onEvent(snapshot(map(soon = 12)), null)

    assertEquals(listOf(7, 12), emitted.map { it.soonDays })
  }

  @Test
  fun observeFailsWithTheFirestoreErrorAndStopsListening() = runTest {
    val outcome =
        async(UnconfinedTestDispatcher(testScheduler)) {
          runCatching { repository.observe(USER).collect {} }
        }
    val error =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

    listener!!.onEvent(null, error)

    assertEquals(error, outcome.await().exceptionOrNull())
    assertEquals(1, removedListeners)
  }

  @Test
  fun cancellingTheCollectorRemovesTheListener() = runTest {
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observe(USER).collect {}
        }

    job.cancel()
    job.join()

    assertEquals(1, removedListeners)
  }

  private companion object {
    const val USER = "alice"
  }
}
