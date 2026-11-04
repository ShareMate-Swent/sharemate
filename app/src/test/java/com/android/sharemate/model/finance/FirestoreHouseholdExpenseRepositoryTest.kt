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
  @Suppress("UNCHECKED_CAST")
  private val callback =
      ArgumentCaptor.forClass(EventListener::class.java)
          as ArgumentCaptor<EventListener<QuerySnapshot>>
  private val repository = FirestoreHouseholdExpenseRepository(firestore)

  @Before
  fun setUp() {
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(households.document("home")).thenReturn(household)
    `when`(household.collection(FirestoreCollections.EXPENSES)).thenReturn(expenses)
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
          clearInvocations(registration)
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
        }
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
