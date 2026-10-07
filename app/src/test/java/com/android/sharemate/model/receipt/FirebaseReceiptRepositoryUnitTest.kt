// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.coroutines.cancellation.CancellationException

class FirebaseReceiptRepositoryUnitTest {
  private lateinit var firestore: FirebaseFirestore
  private lateinit var collection: CollectionReference
  private lateinit var repository: FirebaseReceiptRepository

  @Before
  fun setUp() {
    firestore = mock(FirebaseFirestore::class.java)
    collection = mock(CollectionReference::class.java)
    `when`(firestore.collection(FirestoreCollections.RECEIPTS)).thenReturn(collection)
    repository = FirebaseReceiptRepository(firestore)
  }

  @Test
  fun getPrivateReceipts_returnsEmptyFlow_whenUserIdIsBlank() =
      runBlocking<Unit> {
        val receipts = repository.getPrivateReceipts("   ").first()
        assertTrue(receipts.isEmpty())
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getPrivateReceipts_emitsOnlyPrivateReceipts() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("ownerId", "user-1")).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Receipt>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getPrivateReceipts("user-1").collect { emittedLists.add(it) }
            }

        val snapshot = mock(QuerySnapshot::class.java)

        val privateReceipt = mock(DocumentSnapshot::class.java)
        `when`(privateReceipt.id).thenReturn("receipt-1")
        `when`(privateReceipt.getString("storeName")).thenReturn("Migros")
        `when`(privateReceipt.getString("ownerId")).thenReturn("user-1")
        `when`(privateReceipt.getString("householdId")).thenReturn(null)

        val sharedReceipt = mock(DocumentSnapshot::class.java)
        `when`(sharedReceipt.id).thenReturn("receipt-2")
        `when`(sharedReceipt.getString("storeName")).thenReturn("Coop")
        `when`(sharedReceipt.getString("ownerId")).thenReturn("user-1")
        `when`(sharedReceipt.getString("householdId")).thenReturn("household-1")

        val invalidReceipt = mock(DocumentSnapshot::class.java)
        `when`(invalidReceipt.id).thenReturn("receipt-3")
        `when`(invalidReceipt.getString("storeName")).thenReturn(" ")

        `when`(snapshot.documents).thenReturn(listOf(privateReceipt, sharedReceipt, invalidReceipt))

        listenerCaptor.value.onEvent(snapshot, null)

        assertEquals(1, emittedLists.size)
        assertEquals(listOf("receipt-1"), emittedLists[0].map { it.id })
        assertEquals("Migros", emittedLists[0][0].storeName)

        job.cancel()
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getPrivateReceipts_handlesError() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("ownerId", "user-1")).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        var caughtError: Throwable? = null
        val job =
            launch(Dispatchers.Unconfined) {
              try {
                repository.getPrivateReceipts("user-1").collect {}
              } catch (e: Exception) {
                caughtError = e
              }
            }

        val exception =
            FirebaseFirestoreException(
                "Permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        listenerCaptor.value.onEvent(null, exception)

        assertTrue(caughtError is FirebaseFirestoreException)

        job.cancel()
      }

  @Test
  fun getSharedReceipts_returnsEmptyFlow_whenHouseholdIdIsBlank() =
      runBlocking<Unit> {
        val receipts = repository.getSharedReceipts("   ").first()
        assertTrue(receipts.isEmpty())
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getSharedReceipts_emitsOnlyValidSharedReceipts() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("householdId", "household-1")).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Receipt>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getSharedReceipts("household-1").collect { emittedLists.add(it) }
            }

        val snapshot = mock(QuerySnapshot::class.java)
        val receipt = mock(DocumentSnapshot::class.java)
        `when`(receipt.id).thenReturn("receipt-4")
        `when`(receipt.getString("storeName")).thenReturn("Aldi")
        `when`(receipt.getLong("totalAmountCents")).thenReturn(3500L)
        `when`(receipt.getString("ownerId")).thenReturn("user-2")
        `when`(receipt.getString("householdId")).thenReturn("household-1")
        val date = Date(1_700_000_000_000L)
        `when`(receipt.getTimestamp("date")).thenReturn(Timestamp(date))

        val invalid = mock(DocumentSnapshot::class.java)
        `when`(invalid.getString("storeName")).thenReturn(" ")

        `when`(snapshot.documents).thenReturn(listOf(receipt, invalid))

        listenerCaptor.value.onEvent(snapshot, null)

        assertEquals(1, emittedLists.size)
        assertEquals(1, emittedLists[0].size)
        assertEquals("receipt-4", emittedLists[0][0].id)
        assertEquals("Aldi", emittedLists[0][0].storeName)
        assertEquals(3500L, emittedLists[0][0].totalAmountCents)
        assertEquals(date, emittedLists[0][0].date)

        job.cancel()
      }

  @Test
  fun addReceipt_failsOnBlankStoreNameOrOwnerId() =
      runBlocking<Unit> {
        assertNull(repository.addReceipt(Receipt(storeName = "  ", ownerId = "user-1")))
        assertNull(repository.addReceipt(Receipt(storeName = "Migros", ownerId = "   ")))
      }

  @Test
  fun addReceipt_succeedsAndTrimsData() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Receipt::class.java))).thenReturn(Tasks.forResult(null))

        val result =
            repository.addReceipt(
                Receipt(
                    storeName = "  Migros  ",
                    totalAmountCents = 1250L,
                    ownerId = "user-1",
                    householdId = "  "))

        assertEquals("generated-id", result)

        val captor = ArgumentCaptor.forClass(Receipt::class.java)
        verify(document).set(captor.capture())

        val saved = captor.value
        assertEquals("generated-id", saved.id)
        assertEquals("Migros", saved.storeName)
        assertEquals(1250L, saved.totalAmountCents)
        assertEquals("user-1", saved.ownerId)
        assertNull(saved.householdId)
      }

  @Test
  fun addReceipt_returnsNullOnGenericException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.set(any(Receipt::class.java)))
            .thenThrow(RuntimeException("Firestore error"))

        val result = repository.addReceipt(Receipt(storeName = "Migros", ownerId = "user-1"))
        assertNull(result)
      }

  @Test
  fun addReceipt_rethrowsCancellationException() = runBlocking<Unit> {
    val document = mock(DocumentReference::class.java)
    `when`(collection.document()).thenReturn(document)
    `when`(document.set(any(Receipt::class.java))).thenThrow(CancellationException("Cancelled"))

    try {
      repository.addReceipt(Receipt(storeName = "Migros", ownerId = "user-1"))
      Assert.fail("Expected CancellationException")
    } catch (e: CancellationException) {
      // Expected
    }
  }

  @Test
  fun deleteReceipt_rethrowsCancellationException() = runBlocking<Unit> {
    val document = mock(DocumentReference::class.java)
    `when`(collection.document("receipt-1")).thenReturn(document)
    `when`(document.delete()).thenThrow(CancellationException("Cancelled"))

    try {
      repository.deleteReceipt("receipt-1")
      Assert.fail("Expected CancellationException")
    } catch (e: CancellationException) {
      // Expected
    }
  }

  @Test
  fun deleteReceipt_failsOnBlankId() =
      runBlocking<Unit> { assertFalse(repository.deleteReceipt("  ")) }

  @Test
  fun deleteReceipt_succeeds() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document("receipt-1")).thenReturn(document)
        `when`(document.delete()).thenReturn(Tasks.forResult(null))

        val result = repository.deleteReceipt("receipt-1")
        assertTrue(result)
        verify(document).delete()
      }

  @Test
  fun deleteReceipt_returnsFalseOnGenericException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document("receipt-1")).thenReturn(document)
        `when`(document.delete()).thenThrow(RuntimeException("Firestore error"))

        val result = repository.deleteReceipt("receipt-1")
        assertFalse(result)
      }

  @Test
  fun toReceiptOrNull_parsesCorrectly() {
    val document = mock(DocumentSnapshot::class.java)
    `when`(document.id).thenReturn("receipt-1")
    `when`(document.getString("storeName")).thenReturn("Migros")
    `when`(document.getLong("totalAmountCents")).thenReturn(1250L)
    val date = Date(1_700_000_000_000L)
    `when`(document.getTimestamp("date")).thenReturn(Timestamp(date))
    `when`(document.getString("ownerId")).thenReturn("user-1")
    `when`(document.getString("householdId")).thenReturn("household-1")

    val receipt = document.toReceiptOrNull()

    assertEquals("receipt-1", receipt?.id)
    assertEquals("Migros", receipt?.storeName)
    assertEquals(1250L, receipt?.totalAmountCents)
    assertEquals("user-1", receipt?.ownerId)
    assertEquals("household-1", receipt?.householdId)
    assertEquals(date, receipt?.date)
  }

  @Test
  fun toReceiptOrNull_rejectsBlankRequiredFields() {
    val document = mock(DocumentSnapshot::class.java)
    `when`(document.getString("storeName")).thenReturn(" ")
    `when`(document.getString("ownerId")).thenReturn("user-1")

    assertNull(document.toReceiptOrNull())

    `when`(document.getString("storeName")).thenReturn("Migros")
    `when`(document.getString("ownerId")).thenReturn(" ")

    assertNull(document.toReceiptOrNull())
  }


}
