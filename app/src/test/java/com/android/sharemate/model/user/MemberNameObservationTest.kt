// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/** Exercises the repository's actual listener/flow lifecycle with controlled SDK callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberNameObservationTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val households = mock(CollectionReference::class.java)
  private val home = mock(DocumentReference::class.java)
  private val directory = mock(CollectionReference::class.java)
  private val householdRegistration = mock(ListenerRegistration::class.java)
  private lateinit var membership: EventListener<DocumentSnapshot>
  private val emissions = mutableListOf<Map<String, String>>()
  private var failure: Throwable? = null
  private val repository = FirebaseMemberNameRepository(firestore)

  init {
    `when`(firestore.collection("households")).thenReturn(households)
    `when`(households.document("home")).thenReturn(home)
    `when`(home.collection("memberNames")).thenReturn(directory)
    `when`(home.addSnapshotListener(any<EventListener<DocumentSnapshot>>())).thenAnswer {
      membership = it.getArgument(0)
      householdRegistration
    }
  }

  private inner class Batch(val ids: List<String>) {
    val query = mock(Query::class.java)
    val registration = mock(ListenerRegistration::class.java)
    lateinit var listener: EventListener<QuerySnapshot>

    init {
      `when`(directory.whereIn(FieldPath.documentId(), ids)).thenReturn(query)
      `when`(query.addSnapshotListener(any<EventListener<QuerySnapshot>>())).thenAnswer {
        listener = it.getArgument(0)
        registration
      }
    }

    fun emit(vararg entries: Pair<String, Any?>) {
      val result = mock(QuerySnapshot::class.java)
      val documents =
          entries.map { (id, name) ->
            mock(DocumentSnapshot::class.java).also {
              `when`(it.id).thenReturn(id)
              `when`(it.get("displayName")).thenReturn(name)
            }
          }
      `when`(result.documents).thenReturn(documents)
      listener.onEvent(result, null)
    }
  }

  private fun batch(vararg ids: String) = Batch(ids.toList())

  private fun household(
      ids: Any? = listOf("owner"),
      type: String? = "SHARED",
      creator: String? = "owner"
  ): DocumentSnapshot =
      mock(DocumentSnapshot::class.java).also {
        `when`(it.get("memberIds")).thenReturn(ids)
        `when`(it.getString("type")).thenReturn(type)
        `when`(it.getString("createdBy")).thenReturn(creator)
      }

  private fun TestScope.start(): Job =
      launch {
            try {
              repository.observeNames("home", "owner").collect { emissions += it }
            } catch (error: Exception) {
              failure = error
            }
          }
          .also { runCurrent() }

  @Test
  fun missingMalformedAndNonMemberHouseholdsNeverQueryNames() = runTest {
    val job = start()
    for (snapshot in
        listOf(
            null,
            household(ids = null),
            household(ids = "invalid"),
            household(ids = listOf(1, false)),
            household(ids = listOf("someone-else")))) {
      membership.onEvent(snapshot, null)
      runCurrent()
      assertEquals(emptyMap<String, String>(), emissions.last())
    }
    verifyNoInteractions(directory)
    job.cancel()
    runCurrent()
    verify(householdRegistration).remove()
  }

  @Test
  fun privateMembershipCannotExposeNamesToAnyoneExceptTheCreator() = runTest {
    val job = start()
    membership.onEvent(household(type = "PRIVATE", creator = "another-user"), null)
    runCurrent()
    assertEquals(emptyMap<String, String>(), emissions.last())
    membership.onEvent(household(type = "PRIVATE", creator = null), null)
    runCurrent()
    verifyNoInteractions(directory)
    val own = batch("owner")
    membership.onEvent(household(type = "PRIVATE"), null)
    runCurrent()
    own.emit("owner" to "Alice")
    runCurrent()
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    job.cancel()
    runCurrent()
    verify(own.registration).remove()
  }

  @Test
  fun memberIdsAreValidatedDeduplicatedAndSortedBeforeQuerying() = runTest {
    val own = batch("friend", "owner")
    val job = start()
    membership.onEvent(
        household(ids = listOf("owner", "", " ", "friend", "bad/path", 12, "owner"), type = null),
        null)
    runCurrent()
    own.emit("owner" to "Alice", "friend" to "Bob")
    runCurrent()
    assertEquals(mapOf("owner" to "Alice", "friend" to "Bob"), emissions.last())
    verify(directory).whereIn(FieldPath.documentId(), listOf("friend", "owner"))
    verifyNoMoreInteractions(directory)
    job.cancel()
    runCurrent()
  }

  @Test
  fun membershipChangesClearStaleNamesAndReplaceOnlyMemberQueries() = runTest {
    val original = batch("friend", "owner")
    val own = batch("owner")
    val job = start()
    membership.onEvent(household(ids = listOf("friend", "owner")), null)
    runCurrent()
    original.emit("friend" to "Bob", "owner" to "Alice")
    runCurrent()
    assertEquals(2, emissions.last().size)
    membership.onEvent(household(), null)
    runCurrent()
    assertTrue(emissions.last().isEmpty())
    verify(original.registration).remove()
    own.emit("owner" to "Alice")
    runCurrent()
    // A late callback from the removed query cannot reintroduce a departed member's name.
    original.emit("friend" to "Stale Bob")
    runCurrent()
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    membership.onEvent(household(ids = emptyList<String>()), null)
    runCurrent()
    assertTrue(emissions.last().isEmpty())
    verify(own.registration).remove()
    verify(home, times(1)).addSnapshotListener(any<EventListener<DocumentSnapshot>>())
    job.cancel()
    runCurrent()
    verify(householdRegistration).remove()
  }

  @Test
  fun nullAndMalformedNameSnapshotsUseSafeFallbacksWithoutLeakingValues() = runTest {
    val ids = listOf("blank", "email", "invalid", "missing", "oversized", "owner")
    val own = batch(*ids.toTypedArray())
    val job = start()
    membership.onEvent(household(ids = ids), null)
    runCurrent()
    own.listener.onEvent(null, null)
    runCurrent()
    assertTrue(emissions.last().isEmpty())
    own.emit(
        "missing" to null,
        "invalid" to 7,
        "blank" to "  ",
        "email" to "private@example.com",
        "oversized" to "x".repeat(81),
        "owner" to " Alice \n Smith ")
    runCurrent()
    assertEquals(mapOf("owner" to "Alice Smith"), emissions.last())
    own.emit()
    runCurrent()
    assertTrue(emissions.last().isEmpty())
    job.cancel()
    runCurrent()
  }

  @Test
  fun householdPermissionFailurePropagatesAndClosesAllListeners() = runTest {
    val own = batch("owner")
    val job = start()
    membership.onEvent(household(), null)
    runCurrent()
    own.emit("owner" to "Alice")
    runCurrent()
    val denied =
        FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    membership.onEvent(null, denied)
    runCurrent()
    assertSame(denied, failure)
    assertTrue(job.isCompleted)
    verify(householdRegistration).remove()
    verify(own.registration).remove()
  }

  @Test
  fun nameQueryFailurePropagatesAndClosesSiblingBatchesAndMembershipListener() = runTest {
    val ids = (0..10).map { if (it == 0) "owner" else "member-$it" }.sorted()
    val first = batch(*ids.take(10).toTypedArray())
    val second = batch(*ids.drop(10).toTypedArray())
    val job = start()
    membership.onEvent(household(ids = ids), null)
    runCurrent()
    val denied =
        FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    second.listener.onEvent(null, denied)
    runCurrent()
    assertSame(denied, failure)
    assertTrue(job.isCompleted)
    verify(householdRegistration).remove()
    verify(first.registration).remove()
    verify(second.registration).remove()
  }
}
