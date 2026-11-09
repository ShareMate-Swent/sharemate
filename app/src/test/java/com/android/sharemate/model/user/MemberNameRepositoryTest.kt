// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class MemberNameRepositoryTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val households = mock(CollectionReference::class.java)
  private val home = mock(DocumentReference::class.java)
  private val names = mock(CollectionReference::class.java)
  private val entry = mock(DocumentReference::class.java)
  private val users = mock(CollectionReference::class.java)
  private val profile = mock(DocumentReference::class.java)
  private val snapshot = mock(DocumentSnapshot::class.java)
  private val repository = FirebaseMemberNameRepository(firestore)

  @Before
  fun setup() {
    `when`(firestore.collection("households")).thenReturn(households)
    `when`(households.document("home")).thenReturn(home)
    `when`(home.collection("memberNames")).thenReturn(names)
    `when`(names.document("owner")).thenReturn(entry)
    `when`(firestore.collection("users")).thenReturn(users)
    `when`(users.document("owner")).thenReturn(profile)
    `when`(profile.get()).thenReturn(Tasks.forResult(snapshot))
    `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(Tasks.forResult(null))
    `when`(entry.delete()).thenReturn(Tasks.forResult(null))
  }

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
    repository.publishOwnName("home", "owner", " Alice  Smith ")
    verify(entry).set(mapOf("displayName" to "Alice Smith"))
    verify(firestore, never()).collection("users")
  }

  @Test
  fun existingPrivateProfileNameIsSupportedWithoutReadingEmail() = runTest {
    `when`(snapshot.get("displayName")).thenReturn(" Alice Smith ")
    repository.publishOwnName("home", "owner", null)
    verify(snapshot).get("displayName")
    verifyNoMoreInteractions(snapshot)
    verify(entry).set(mapOf("displayName" to "Alice Smith"))
  }

  @Test
  fun missingNameClearsStaleDirectoryNameRatherThanPublishingEmail() = runTest {
    `when`(snapshot.get("displayName")).thenReturn("email@example.com")
    repository.publishOwnName("home", "owner", "email@example.com")
    verify(entry).delete()
    verify(snapshot).get("displayName")
    verifyNoMoreInteractions(snapshot)
  }

  @Test
  fun namesAreQueriedPerMemberBatchAndListenersCloseWithTheirCollector() = runTest {
    val members = (0..11).map { if (it == 0) "owner" else "member-$it" }.sorted()
    val metadata = mock(SnapshotMetadata::class.java)
    val household = mock(DocumentSnapshot::class.java)
    `when`(household.metadata).thenReturn(metadata)
    `when`(household.get("memberIds")).thenReturn(members)
    `when`(household.getString("type")).thenReturn("SHARED")
    val registration = mock(ListenerRegistration::class.java)
    lateinit var membership: EventListener<DocumentSnapshot>
    `when`(
            home.addSnapshotListener(
                eq(MetadataChanges.INCLUDE), any<EventListener<DocumentSnapshot>>()))
        .thenAnswer {
          membership = it.getArgument(1)
          membership.onEvent(household, null)
          registration
        }
    val queries =
        members.chunked(10).map { ids ->
          val query = mock(Query::class.java)
          val result = mock(QuerySnapshot::class.java)
          val listener = mock(ListenerRegistration::class.java)
          val documents =
              ids.map { id ->
                mock(DocumentSnapshot::class.java).also {
                  `when`(it.id).thenReturn(id)
                  `when`(it.get("displayName")).thenReturn("Name $id")
                }
              }
          `when`(result.documents).thenReturn(documents)
          `when`(result.metadata).thenReturn(metadata)
          `when`(names.whereIn(FieldPath.documentId(), ids)).thenReturn(query)
          `when`(
                  query.addSnapshotListener(
                      eq(MetadataChanges.INCLUDE), any<EventListener<QuerySnapshot>>()))
              .thenAnswer {
                it.getArgument<EventListener<QuerySnapshot>>(1).onEvent(result, null)
                listener
              }
          Triple(ids, query, listener)
        }
    var resolved = emptyMap<String, String>()
    val collector = launch { repository.observeNames("home", "owner").collect { resolved = it } }
    runCurrent()
    assertEquals(12, resolved.size)
    membership.onEvent(household, null)
    runCurrent()
    queries.forEach { (ids, _, _) -> verify(names).whereIn(FieldPath.documentId(), ids) }
    collector.cancel()
    runCurrent()
    verify(registration).remove()
    queries.forEach { (_, _, listener) -> verify(listener).remove() }
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
      repository.publishOwnName("home", "owner", null)
    }
    verify(entry, times(3)).delete()
    verify(snapshot, times(3)).get("displayName")
    verifyNoMoreInteractions(snapshot)
    assertEquals("x".repeat(80), ownerDisplayName("x".repeat(80)))
  }

  @Test
  fun aFailedPrivateProfileReadIsPropagatedWithoutPublishingAnything() = runTest {
    val denied =
        FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    `when`(profile.get()).thenReturn(Tasks.forException(denied))
    assertSame(
        denied, runCatching { repository.publishOwnName("home", "owner", null) }.exceptionOrNull())
    verifyNoInteractions(entry)
  }

  @Test
  fun cancellingProfileLookupPreventsALaterPublication() = runTest {
    val pending = TaskCompletionSource<DocumentSnapshot>()
    `when`(profile.get()).thenReturn(pending.task)
    val publication = launch { repository.publishOwnName("home", "owner", null) }
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
  fun publicationWaitsForAcknowledgmentAndPropagatesWriteDenial() = runTest {
    val pending = TaskCompletionSource<Void>()
    `when`(entry.set(mapOf("displayName" to "Alice Smith"))).thenReturn(pending.task)
    var failure: Throwable? = null
    val publication = launch {
      failure =
          runCatching { repository.publishOwnName("home", "owner", "Alice Smith") }
              .exceptionOrNull()
    }
    runCurrent()
    assertTrue(publication.isActive)
    val denied =
        FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    pending.setException(denied)
    runCurrent()
    assertTrue(publication.isCompleted)
    assertSame(denied, failure)
  }

  @Test
  fun failedRemovalOfAStaleNameIsNotReportedAsSuccess() = runTest {
    val denied =
        FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    `when`(entry.delete()).thenReturn(Tasks.forException(denied))
    assertSame(
        denied, runCatching { repository.publishOwnName("home", "owner", null) }.exceptionOrNull())
  }
}
