// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreHouseholdExpenseRepositoryTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val households = mock(CollectionReference::class.java)
  private val household = mock(DocumentReference::class.java)
  private val expenses = mock(CollectionReference::class.java)
  private val registration = mock(ListenerRegistration::class.java)
  private val parentRegistration = mock(ListenerRegistration::class.java)
  @Suppress("UNCHECKED_CAST")
  private val callback =
      ArgumentCaptor.forClass(EventListener::class.java)
          as ArgumentCaptor<EventListener<QuerySnapshot>>
  @Suppress("UNCHECKED_CAST")
  private val parentCallback =
      ArgumentCaptor.forClass(EventListener::class.java)
          as ArgumentCaptor<EventListener<DocumentSnapshot>>
  private val repository = FirestoreHouseholdExpenseRepository(firestore)

  @Before
  fun setUp() {
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(households.document("home")).thenReturn(household)
    `when`(household.collection(FirestoreCollections.EXPENSES)).thenReturn(expenses)
    `when`(household.addSnapshotListener(eq(MetadataChanges.INCLUDE), parentCallback.capture()))
        .thenReturn(parentRegistration)
    `when`(expenses.addSnapshotListener(eq(MetadataChanges.INCLUDE), callback.capture()))
        .thenReturn(registration)
  }

  @Test
  fun validatesPathsBeforeAccessAndDefersRegistrationUntilCollection() {
    for (id in listOf("", " ", "home/expenses")) {
      assertThrows(IllegalArgumentException::class.java) { repository.observeHouseholdExpenses(id) }
    }
    repository.observeHouseholdExpenses("home")
    verifyNoInteractions(firestore, households, household, expenses)
  }

  @Test
  fun skipsCachedAndPendingMetadataThenEmitsImmutableDistinctEmptyAndPopulatedLists() = runTest {
    val received = mutableListOf<List<SharedExpense>>()
    val observer = launch { repository.observeHouseholdExpenses("home").collect { received += it } }
    runCurrent()
    parentCallback.value.onEvent(parentSnapshot(cache = true), null)
    parentCallback.value.onEvent(parentSnapshot(pending = true), null)
    parentCallback.value.onEvent(parentSnapshot(exists = true), null)
    callback.value.onEvent(snapshot(cache = true), null)
    callback.value.onEvent(snapshot(pending = true), null)
    runCurrent()
    assertTrue(received.isEmpty())
    callback.value.onEvent(snapshot(), null)
    callback.value.onEvent(snapshot(), null)
    callback.value.onEvent(snapshot(documents = listOf(document())), null)
    callback.value.onEvent(snapshot(documents = listOf(document())), null)
    runCurrent()
    assertEquals(2, received.size)
    assertTrue(received.first().isEmpty())
    assertEquals(
        listOf(SharedExpense("expense", "home", "alice", 100, mapOf("bob" to 100L))),
        received.last())
    assertThrows(UnsupportedOperationException::class.java) {
      (received.last() as MutableList<SharedExpense>).clear()
    }
    observer.cancelAndJoin()
    verify(registration).remove()
    verify(parentRegistration).remove()
  }

  @Test
  fun sourceErrorsNullSnapshotsAndMalformedRecordsTerminateWithoutPartialFallbackAndReleaseListener() =
      runTest {
        val failure =
            FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        val cases =
            listOf<Pair<QuerySnapshot?, FirebaseFirestoreException?>>(
                null to failure,
                null to null,
                snapshot(documents = listOf(document(), document(valid = false))) to null)
        for ((value, error) in cases) {
          clearInvocations(registration, parentRegistration)
          val received = mutableListOf<List<SharedExpense>>()
          var cause: Throwable? = null
          val observer = launch {
            repository
                .observeHouseholdExpenses("home")
                .catch { cause = it }
                .collect { received += it }
          }
          runCurrent()
          callback.value.onEvent(value, error)
          runCurrent()
          observer.join()
          assertTrue(received.isEmpty())
          assertNotNull(cause)
          if (error != null) assertSame(error, cause)
          else assertTrue(cause is IllegalArgumentException || cause is IllegalStateException)
          verify(registration).remove()
          verify(parentRegistration).remove()
        }
      }

  @Test
  fun parentRevocationNullAndMissingServerSnapshotsCloseActiveObservationAndRemoveBothListeners() =
      runTest {
        val denied =
            FirebaseFirestoreException(
                "membership revoked", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        for ((value, error) in
            listOf<Pair<DocumentSnapshot?, FirebaseFirestoreException?>>(
                null to denied,
                null to null,
                parentSnapshot() to null,
                parentSnapshot(exists = true, type = "PRIVATE") to null,
                parentSnapshot(exists = true, type = null) to null)) {
          clearInvocations(registration, parentRegistration)
          var cause: Throwable? = null
          val received = mutableListOf<List<SharedExpense>>()
          val observer = launch {
            repository
                .observeHouseholdExpenses("home")
                .catch { cause = it }
                .collect { received += it }
          }
          runCurrent()
          callback.value.onEvent(snapshot(), null)
          runCurrent()
          assertEquals(listOf(emptyList<SharedExpense>()), received)
          parentCallback.value.onEvent(value, error)
          runCurrent()
          observer.join()
          if (error != null) assertSame(error, cause)
          else if (value == null) assertTrue(cause is IllegalStateException)
          else
              assertEquals(
                  FirebaseFirestoreException.Code.PERMISSION_DENIED,
                  (cause as? FirebaseFirestoreException)?.code)
          verify(registration).remove()
          verify(parentRegistration).remove()
        }
      }

  @Test
  fun expenseListenerRegistrationFailureStillReleasesTheParentListener() = runTest {
    val failure = IllegalStateException("data source closed")
    doThrow(failure)
        .`when`(expenses)
        .addSnapshotListener(eq(MetadataChanges.INCLUDE), callback.capture())
    var cause: Throwable? = null
    val observer = launch {
      repository
          .observeHouseholdExpenses("home")
          .catch { cause = it }
          .collect { fail("Unexpected expense list") }
    }
    runCurrent()
    observer.join()
    // Coroutine stack-trace recovery may copy an exception thrown by the flow producer.
    assertTrue(cause is IllegalStateException)
    assertEquals(failure.message, cause?.message)
    assertTrue(cause === failure || cause?.cause === failure)
    verify(parentRegistration).remove()
    verify(registration, never()).remove()
  }

  private fun parentSnapshot(
      cache: Boolean = false,
      pending: Boolean = false,
      exists: Boolean = false,
      type: String? = "SHARED"
  ): DocumentSnapshot {
    val metadata = mock(SnapshotMetadata::class.java)
    `when`(metadata.isFromCache).thenReturn(cache)
    `when`(metadata.hasPendingWrites()).thenReturn(pending)
    val parent = mock(DocumentSnapshot::class.java)
    `when`(parent.metadata).thenReturn(metadata)
    `when`(parent.exists()).thenReturn(exists)
    `when`(parent.get("type")).thenReturn(type)
    return parent
  }

  private fun snapshot(
      cache: Boolean = false,
      pending: Boolean = false,
      documents: List<DocumentSnapshot> = emptyList()
  ): QuerySnapshot {
    val metadata = mock(SnapshotMetadata::class.java)
    `when`(metadata.isFromCache).thenReturn(cache)
    `when`(metadata.hasPendingWrites()).thenReturn(pending)
    val snapshot = mock(QuerySnapshot::class.java)
    `when`(snapshot.metadata).thenReturn(metadata)
    `when`(snapshot.documents).thenReturn(documents)
    return snapshot
  }

  private fun document(valid: Boolean = true): DocumentSnapshot {
    val document = mock(DocumentSnapshot::class.java)
    `when`(document.id).thenReturn("expense")
    `when`(document.data)
        .thenReturn(
            mapOf(
                "id" to "expense",
                "householdId" to "home",
                "paidBy" to "alice",
                "totalAmountCents" to if (valid) 100L else 99L,
                "shares" to mapOf("bob" to 100L)))
    return document
  }
}
