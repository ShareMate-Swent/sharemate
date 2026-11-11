// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class MemberNameRepositoryTest {
  private val firestore = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
  private val home = firestore.collection("households").document("home")
  private val entry = home.collection("memberNames").document("owner")
  private val profile = firestore.collection("users").document("owner")
  private val snapshot = mock(DocumentSnapshot::class.java)
  private val repository = FirebaseMemberNameRepository(firestore)
  private val denied = FirebaseFirestoreException("Denied", PERMISSION_DENIED)

  @Before
  fun setup() {
    clearInvocations(firestore)
    `when`(profile.get()).thenReturn(Tasks.forResult(snapshot))
    `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(Tasks.forResult(null))
    `when`(entry.delete()).thenReturn(Tasks.forResult(null))
  }

  private suspend fun publish(name: String? = null) =
      repository.publishOwnName("home", "owner", name)

  private suspend fun queue(name: String? = null) = repository.queueOwnName("home", "owner", name)

  @Test
  fun namesAreBoundedNormalizedAndNeverFallBackToEmail() {
    assertEquals("Alice Smith", ownerDisplayName("  Alice \n Smith  "))
    for (raw in
        listOf(
            null,
            "",
            " \t\n",
            "alice@example.com",
            "Alice\n<alice@example.com>",
            "x".repeat(81))) assertNull(ownerDisplayName(raw))
  }

  @Test
  fun authNamePublishesOnlyNameAndNeverReadsPrivateProfile() = runTest {
    publish(" Alice  Smith ")
    verify(entry).set(mapOf("displayName" to "Alice Smith"))
    verify(firestore, never()).collection("users")
  }

  @Test
  fun existingPrivateProfileNameIsSupportedWithoutReadingEmail() = runTest {
    `when`(snapshot.get("displayName")).thenReturn(" Alice Smith ")
    publish(null)
    verify(snapshot).get("displayName")
    verifyNoMoreInteractions(snapshot)
    verify(entry).set(mapOf("displayName" to "Alice Smith"))
  }

  @Test
  fun missingNameClearsStaleDirectoryNameRatherThanPublishingEmail() = runTest {
    `when`(snapshot.get("displayName")).thenReturn("email@example.com")
    publish("email@example.com")
    verify(entry).delete()
    verify(snapshot).get("displayName")
    verifyNoMoreInteractions(snapshot)
  }

  @Test
  fun namesAreQueriedPerMemberBatchAndListenersCloseWithTheirCollector() = runTest {
    with(MemberNameObservationFixture()) {
      val ids = (0..11).map { if (it == 0) "owner" else "member-$it" }.sorted()
      val requests = ids.chunked(10).map { batch(*it.toTypedArray()) }
      val collector = start()
      emitMembership(household(ids))
      requests.forEach { request ->
        request.emit(*request.ids.map { it to "Name $it" }.toTypedArray())
      }
      assertEquals(12, emissions.last().size)
      emitMembership(household(ids))
      requests.forEach { verify(directory).whereIn(FieldPath.documentId(), it.ids) }
      stop(collector)
      verify(householdRegistration).remove()
      requests.forEach { verify(it.registration).remove() }
    }
  }

  @Test
  fun invalidHouseholdAndUserIdsAreRejectedBeforeAnyFirestoreAccess() = runTest {
    for (invalid in listOf("", " ", "bad/path")) {
      assertThrows(IllegalArgumentException::class.java) {
        repository.observeNames(invalid, "owner")
      }
      assertThrows(IllegalArgumentException::class.java) {
        repository.observeNames("home", invalid)
      }
      assertTrue(
          runCatching { repository.publishOwnName(invalid, "owner", "Alice") }.exceptionOrNull()
              is IllegalArgumentException)
      assertTrue(
          runCatching { repository.publishOwnName("home", invalid, "Alice") }.exceptionOrNull()
              is IllegalArgumentException)
    }
    verifyNoInteractions(firestore)
  }

  @Test
  fun legacyProfilesWithoutStringNamesClearTheEntryWithoutReadingOtherFields() = runTest {
    for (raw in listOf(null, 7, "  ")) {
      `when`(snapshot.get("displayName")).thenReturn(raw)
      publish(null)
    }
    verify(entry, times(3)).delete()
    verify(snapshot, times(3)).get("displayName")
    verifyNoMoreInteractions(snapshot)
    assertEquals("x".repeat(80), ownerDisplayName("x".repeat(80)))
  }

  @Test
  fun aFailedPrivateProfileReadIsPropagatedWithoutPublishingAnything() = runTest {
    `when`(profile.get()).thenReturn(Tasks.forException(denied))
    assertSame(denied, runCatching { publish(null) }.exceptionOrNull())
    verifyNoInteractions(entry)
  }

  @Test
  fun cancellingProfileLookupPreventsALaterPublication() = runTest {
    val pending = TaskCompletionSource<DocumentSnapshot>()
    `when`(profile.get()).thenReturn(pending.task)
    val publication = launch { publish(null) }
    runCurrent()
    publication.cancel()
    runCurrent()
    `when`(snapshot.get("displayName")).thenReturn("Alice Smith")
    pending.setResult(snapshot)
    runCurrent()
    assertTrue(publication.isCancelled)
    verifyNoInteractions(entry)
  }

  @Test
  fun publicationQueuesOfflineAndReportsLaterServerDenial() = runTest {
    val pending = TaskCompletionSource<Void>()
    `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(pending.task)
    publish("Alice Smith") // Compatibility caller also returns.
    val acknowledgement = queue("Alice Smith")
    assertFalse(acknowledgement.isCompleted)
    pending.setException(denied)
    assertSame(denied, acknowledgement.await().exceptionOrNull())
  }

  @Test
  fun pendingSetAndDeleteConfirmAfterReconnectionWithoutLeakingAwaiters() = runTest {
    for (name in listOf("Alice Smith", null)) {
      val pending = TaskCompletionSource<Void>()
      `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(pending.task)
      `when`(entry.delete()).thenReturn(pending.task)
      val acknowledgement = repository.queueOwnName("home", "owner", name)
      val waiter = launch { acknowledgement.await() }
      runCurrent()
      waiter.cancelAndJoin()
      assertFalse(acknowledgement.isCancelled)
      pending.setResult(null)
      assertTrue(acknowledgement.await().isSuccess)
    }
  }

  @Test
  fun rejectedOrCancelledSubmissionsAreNeverReportedAsConfirmed() = runTest {
    val denied = IllegalStateException("Submission failed")
    `when`(entry.delete()).thenThrow(denied)
    assertSame(denied, runCatching { queue(null) }.exceptionOrNull())
    `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(Tasks.forCanceled())
    assertTrue(runCatching { queue("Alice Smith") }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun legacyImplementationsRemainCompatibleWithTheQueuedEntryPoint() = runTest {
    var called = false
    val legacy =
        object : MemberNameRepository {
          override fun observeNames(householdId: String, viewerId: String) =
              kotlinx.coroutines.flow.emptyFlow<Map<String, String>>()

          override suspend fun publishOwnName(
              householdId: String,
              userId: String,
              authName: String?
          ) {
            called = true
          }
        }
    assertTrue(legacy.queueOwnName("home", "owner", "Alice").await().isSuccess)
    assertTrue(called)
  }

  @Test
  fun failedRemovalOfAStaleNameIsNotReportedAsSuccess() = runTest {
    `when`(entry.delete()).thenReturn(Tasks.forException(denied))
    assertSame(denied, runCatching { publish(null) }.exceptionOrNull())
  }
}
