// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.google.firebase.firestore.*
import com.google.firebase.firestore.MetadataChanges.INCLUDE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Exercises actual listener/flow lifecycle with controlled SDK callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberNameObservationTest : MemberNameObservationFixture() {
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
      emitMembership(snapshot, null)
      assertEquals(emptyMap<String, String>(), emissions.last())
    }
    verifyNoInteractions(directory)
    stop(job)
    verify(householdRegistration).remove()
  }

  @Test
  fun privateMembershipCannotExposeNamesToAnyoneExceptTheCreator() = runTest {
    val job = start()
    emitMembership(household(type = "PRIVATE", creator = "another-user"), null)
    assertEquals(emptyMap<String, String>(), emissions.last())
    emitMembership(household(type = "PRIVATE", creator = null), null)
    verifyNoInteractions(directory)
    val own = batch("owner")
    emitMembership(household(type = "PRIVATE"), null)
    own.emit("owner" to "Alice")
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    stop(job)
    verify(own.registration).remove()
  }

  @Test
  fun memberIdsAreValidatedDeduplicatedAndSortedBeforeQuerying() = runTest {
    val own = batch("friend", "owner")
    val job = start()
    emitMembership(
        household(ids = listOf("owner", "", " ", "friend", "bad/path", 12, "owner"), type = null),
        null)
    own.emit("owner" to "Alice", "friend" to "Bob")
    assertEquals(mapOf("owner" to "Alice", "friend" to "Bob"), emissions.last())
    verify(directory).whereIn(FieldPath.documentId(), listOf("friend", "owner"))
    verifyNoMoreInteractions(directory)
    stop(job)
  }

  @Test
  fun membershipChangesClearStaleNamesAndReplaceOnlyMemberQueries() = runTest {
    val original = batch("friend", "owner")
    val own = batch("owner")
    val job = start()
    emitMembership(household(ids = listOf("friend", "owner")), null)
    original.emit("friend" to "Bob", "owner" to "Alice")
    assertEquals(2, emissions.last().size)
    emitMembership(household(), null)
    assertTrue(emissions.last().isEmpty())
    verify(original.registration).remove()
    own.emit("owner" to "Alice")
    // A late callback from the removed query cannot reintroduce a departed member's name.
    original.emit("friend" to "Stale Bob")
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    emitMembership(household(ids = emptyList<String>()), null)
    assertTrue(emissions.last().isEmpty())
    verify(own.registration).remove()
    verify(home, times(1)).addSnapshotListener(eq(INCLUDE), any())
    stop(job)
    verify(householdRegistration).remove()
  }

  @Test
  fun nullAndMalformedNameSnapshotsUseSafeFallbacksWithoutLeakingValues() = runTest {
    val ids = listOf("blank", "email", "invalid", "missing", "oversized", "owner")
    val own = batch(*ids.toTypedArray())
    val job = start()
    emitMembership(household(ids = ids), null)
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
    assertEquals(mapOf("owner" to "Alice Smith"), emissions.last())
    own.emit()
    assertTrue(emissions.last().isEmpty())
    stop(job)
  }

  @Test
  fun householdPermissionFailurePropagatesAndClosesAllListeners() = runTest {
    val own = batch("owner")
    val job = start()
    emitMembership(household(), null)
    own.emit("owner" to "Alice")
    emitMembership(null, denied)
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
    emitMembership(household(ids = ids), null)
    second.listener.onEvent(null, denied)
    runCurrent()
    assertSame(denied, failure)
    assertTrue(job.isCompleted)
    verify(householdRegistration).remove()
    verify(first.registration).remove()
    verify(second.registration).remove()
  }

  @Test
  fun cachedOrPendingMembershipNeverAuthorizesNameQueries() = runTest {
    val job = start()
    emitMembership(household(cached = true), null)
    assertEquals(emptyMap<String, String>(), emissions.last())
    verifyNoInteractions(directory)
    emitMembership(household(pending = true), null)
    verifyNoInteractions(directory)
    val own = batch("owner")
    emitMembership(household(), null)
    own.emit("owner" to "Alice")
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    stop(job)
  }

  @Test
  fun cachedOrPendingNamesStayHiddenUntilServerConfirmed() = runTest {
    val own = batch("owner")
    val job = start()
    emitMembership(household(), null)
    own.emit("owner" to "Stale Alice", cached = true)
    assertTrue(emissions.last().isEmpty())
    own.emit("owner" to "Pending Alice", pending = true)
    assertTrue(emissions.last().isEmpty())
    own.emit("owner" to "Alice")
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    own.emit("owner" to "Stale Alice", cached = true)
    assertTrue(emissions.last().isEmpty())
    stop(job)
  }

  @Test
  fun losingServerConfirmationClearsNamesAndStopsPreviousQueries() = runTest {
    val own = batch("owner")
    val job = start()
    emitMembership(household(), null)
    own.emit("owner" to "Alice")
    assertEquals(mapOf("owner" to "Alice"), emissions.last())
    emitMembership(household(cached = true), null)
    assertTrue(emissions.last().isEmpty())
    verify(own.registration).remove()
    own.emit("owner" to "Stale Alice")
    assertTrue(emissions.last().isEmpty())
    stop(job)
  }

  @Test
  fun newObservationMustConfirmMembershipAgain() = runTest {
    val own = batch("owner")
    var job = start()
    emitMembership(household(), null)
    own.emit("owner" to "Alice")
    stop(job)
    emissions.clear()
    job = start()
    emitMembership(household(cached = true), null)
    own.emit("owner" to "Stale Alice")
    assertTrue(emissions.isNotEmpty())
    assertTrue(emissions.all { it.isEmpty() })
    stop(job)
  }
}
