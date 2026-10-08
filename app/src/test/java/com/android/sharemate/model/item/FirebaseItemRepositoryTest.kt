// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: AI Assistant
package com.android.sharemate.model.item

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FirebaseItemRepositoryTest {

  private lateinit var firestore: FirebaseFirestore
  private lateinit var collection: CollectionReference
  private lateinit var repository: FirebaseItemRepository

  @Before
  fun setUp() {
    firestore = mock(FirebaseFirestore::class.java)
    collection = mock(CollectionReference::class.java)
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    repository = FirebaseItemRepository(firestore)
  }

  @Test
  fun getPrivateItems_returnsEmptyFlow_whenUserIdIsBlank() =
      runBlocking<Unit> {
        val items = repository.getPrivateItems("   ").first()
        assertTrue(items.isEmpty())
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getPrivateItems_emitsFilteredItems() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("ownerId", "user-1")).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Item>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getPrivateItems("user-1").collect { emittedLists.add(it) }
            }

        verify(query).addSnapshotListener(any())

        val snapshot = mock(QuerySnapshot::class.java)

        val docPrivate = mock(DocumentSnapshot::class.java)
        `when`(docPrivate.id).thenReturn("item-1")
        `when`(docPrivate.getString("name")).thenReturn("Apple")
        `when`(docPrivate.getString("ownerId")).thenReturn("user-1")
        `when`(docPrivate.getString("householdId")).thenReturn(null)

        val docShared = mock(DocumentSnapshot::class.java)
        `when`(docShared.id).thenReturn("item-2")
        `when`(docShared.getString("name")).thenReturn("Banana")
        `when`(docShared.getString("ownerId")).thenReturn("user-1")
        `when`(docShared.getString("householdId")).thenReturn("household-1")

        val docInvalid = mock(DocumentSnapshot::class.java)
        `when`(docInvalid.id).thenReturn("item-3")
        `when`(docInvalid.getString("name")).thenReturn("  ")

        `when`(snapshot.documents).thenReturn(listOf(docPrivate, docShared, docInvalid))

        listenerCaptor.value.onEvent(snapshot, null)

        assertEquals(1, emittedLists.size)
        val firstEmission = emittedLists[0]
        assertEquals(1, firstEmission.size)
        assertEquals("item-1", firstEmission[0].id)
        assertEquals("Apple", firstEmission[0].name)

        job.cancel()
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getPrivateItems_handlesError() =
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
                repository.getPrivateItems("user-1").collect {}
              } catch (e: Exception) {
                caughtError = e
              }
            }

        val exception =
            FirebaseFirestoreException("Error", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        listenerCaptor.value.onEvent(null, exception)

        assertTrue(caughtError is FirebaseFirestoreException)

        job.cancel()
      }

  @Test
  fun getSharedItems_returnsEmptyFlow_whenHouseholdIdIsBlank() =
      runBlocking<Unit> {
        val items = repository.getSharedItems("").first()
        assertTrue(items.isEmpty())
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getSharedItems_emitsItems() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("householdId", "house-1")).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Item>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getSharedItems("house-1").collect { emittedLists.add(it) }
            }

        val snapshot = mock(QuerySnapshot::class.java)
        val doc = mock(DocumentSnapshot::class.java)
        `when`(doc.id).thenReturn("item-1")
        `when`(doc.getString("name")).thenReturn("Carrot")
        `when`(doc.getString("ownerId")).thenReturn("user-2")
        `when`(doc.getString("householdId")).thenReturn("house-1")
        val date = Date(1_000_000_000L)
        `when`(doc.getTimestamp("expirationDate")).thenReturn(Timestamp(date))

        `when`(snapshot.documents).thenReturn(listOf(doc))

        listenerCaptor.value.onEvent(snapshot, null)

        assertEquals(1, emittedLists.size)
        assertEquals(1, emittedLists[0].size)
        val emittedItem = emittedLists[0][0]
        assertEquals("item-1", emittedItem.id)
        assertEquals("Carrot", emittedItem.name)
        assertEquals("user-2", emittedItem.ownerId)
        assertEquals("house-1", emittedItem.householdId)
        assertEquals(date, emittedItem.expirationDate)

        job.cancel()
      }

  @Test
  fun addItem_failsOnBlankNameOrOwnerId() =
      runBlocking<Unit> {
        assertNull(repository.addItem(Item(name = "  ", ownerId = "user-1")))
        assertNull(repository.addItem(Item(name = "Apple", ownerId = "")))
      }

  @Test
  fun addItem_succeedsAndTrimsData() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenReturn(Tasks.forResult(null))

        val result =
            repository.addItem(Item(name = "  Banana  ", ownerId = "user-1", householdId = "  "))

        assertEquals("generated-id", result)

        val captor = ArgumentCaptor.forClass(Item::class.java)
        verify(document).set(captor.capture())

        val savedItem = captor.value
        assertEquals("generated-id", savedItem.id)
        assertEquals("Banana", savedItem.name)
        assertEquals("user-1", savedItem.ownerId)
        assertNull(savedItem.householdId)
      }

  @Test
  fun addItem_rethrowsCancellationException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenThrow(CancellationException("Cancelled"))

        try {
          repository.addItem(Item(name = "Apple", ownerId = "user-1"))
          Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
          // Expected
        }
      }

  @Test
  fun addItem_returnsNullOnGenericException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenThrow(RuntimeException("Firestore error"))

        val result = repository.addItem(Item(name = "Apple", ownerId = "user-1"))
        assertNull(result)
      }

  @Test
  fun deleteItem_failsOnBlankItemId() =
      runBlocking<Unit> { assertFalse(repository.deleteItem("  ")) }

  @Test
  fun deleteItem_succeeds() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document("item-1")).thenReturn(document)
        `when`(document.delete()).thenReturn(Tasks.forResult(null))

        val result = repository.deleteItem("item-1")
        assertTrue(result)
        verify(document).delete()
      }

  @Test
  fun deleteItem_rethrowsCancellationException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document("item-1")).thenReturn(document)
        `when`(document.delete()).thenThrow(CancellationException("Cancelled"))

        try {
          repository.deleteItem("item-1")
          Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
          // Expected
        }
      }

  @Test
  fun deleteItem_returnsFalseOnGenericException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document("item-1")).thenReturn(document)
        `when`(document.delete()).thenThrow(RuntimeException("Firestore error"))

        val result = repository.deleteItem("item-1")
        assertFalse(result)
      }

  @Test
  fun toItemOrNull_parsesCorrectly() {
    val doc = mock(DocumentSnapshot::class.java)
    `when`(doc.id).thenReturn("item-1")
    `when`(doc.getString("name")).thenReturn("Apple")
    `when`(doc.getString("ownerId")).thenReturn("user-1")
    `when`(doc.getString("householdId")).thenReturn("house-1")

    val date = Date()
    `when`(doc.getTimestamp("expirationDate")).thenReturn(Timestamp(date))

    val item = doc.toItemOrNull()

    assertEquals("item-1", item?.id)
    assertEquals("Apple", item?.name)
    assertEquals("user-1", item?.ownerId)
    assertEquals("house-1", item?.householdId)
    assertEquals(date, item?.expirationDate)
  }
}
