// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: AI Assistant
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.Filter
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

  @Test
  fun inventoryReadsStoredImageAndAttribution() {
    val doc = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
    `when`(doc.id).thenReturn("milk")
    `when`(doc.getString("name")).thenReturn("Milk")
    `when`(doc.getString("ownerId")).thenReturn("owner")
    `when`(doc.get("image"))
        .thenReturn(
            mapOf(
                "origin" to "REMOTE_IMAGE",
                "reference" to "https://example.org/milk.jpg",
                "author" to "Jane",
                "sourceUrl" to "https://example.org/photo",
                "license" to "by"))
    val image = doc.toItemOrNull()!!.image!!
    assertEquals(ItemImageOrigin.REMOTE_IMAGE, image.origin)
    assertEquals("https://example.org/milk.jpg", image.reference)
    assertEquals("Jane", image.author)
    assertEquals("https://example.org/photo", image.sourceUrl)
    assertEquals("by", image.license)
  }

  @Test
  fun invalidOptionalImagesDoNotDiscardInventoryItems() {
    val doc = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
    `when`(doc.id).thenReturn("milk")
    `when`(doc.getString("name")).thenReturn("Milk")
    `when`(doc.getString("ownerId")).thenReturn("owner")
    listOf(
            null,
            "invalid",
            mapOf("reference" to ""),
            mapOf("origin" to "UNKNOWN", "reference" to "photo"))
        .forEach {
          `when`(doc.get("image")).thenReturn(it)
          assertEquals("Milk", doc.toItemOrNull()!!.name)
          assertNull(doc.toItemOrNull()!!.image)
        }
    `when`(doc.get("image"))
        .thenReturn(mapOf("origin" to "PERSONAL_PHOTO", "reference" to "content://photos/42"))
    assertEquals(ItemImageOrigin.PERSONAL_PHOTO, doc.toItemOrNull()!!.image!!.origin)
  }

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
  fun getFridgeItems_returnsEmptyFlow_whenUserOrHouseholdIdIsBlank() =
      runBlocking<Unit> {
        assertTrue(repository.getFridgeItems("   ", null).first().isEmpty())
        assertTrue(repository.getFridgeItems("user-1", " ").first().isEmpty())
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getFridgeItems_withoutHouseholdQueriesOnlyCurrentUsersPrivateItems() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("ownerId", "user-1")).thenReturn(query)
        `when`(query.whereEqualTo("householdId", null)).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Item>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getFridgeItems("user-1", null).collect { emittedLists.add(it) }
            }

        verify(query).addSnapshotListener(any())

        val snapshot = mock(QuerySnapshot::class.java)

        val docPrivate = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
        `when`(docPrivate.id).thenReturn("item-1")
        `when`(docPrivate.getString("name")).thenReturn("Apple")
        `when`(docPrivate.getString("ownerId")).thenReturn("user-1")
        `when`(docPrivate.getString("householdId")).thenReturn(null)

        `when`(snapshot.documents).thenReturn(listOf(docPrivate))

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
  fun getFridgeItems_withHouseholdQueriesSharedAndOwnPersonalItems() =
      runBlocking<Unit> {
        val householdQuery = mock(Query::class.java)
        val filteredQuery = mock(Query::class.java)
        `when`(collection.whereEqualTo("householdId", "house-1")).thenReturn(householdQuery)
        `when`(householdQuery.where(any(Filter::class.java))).thenReturn(filteredQuery)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(filteredQuery.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Item>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getFridgeItems("user-1", "house-1").collect { emittedLists.add(it) }
            }

        val snapshot = mock(QuerySnapshot::class.java)
        val shared = mock(DocumentSnapshot::class.java)
        `when`(shared.id).thenReturn("item-1")
        `when`(shared.getString("name")).thenReturn("Carrot")
        `when`(shared.getString("ownerId")).thenReturn("user-2")
        `when`(shared.getString("householdId")).thenReturn("house-1")
        val date = Date(1_000_000_000L)
        `when`(shared.getTimestamp("expirationDate")).thenReturn(Timestamp(date))

        val personal = mock(DocumentSnapshot::class.java)
        `when`(personal.id).thenReturn("item-2")
        `when`(personal.getString("name")).thenReturn("Apple")
        `when`(personal.getString("ownerId")).thenReturn("user-1")
        `when`(personal.getString("householdId")).thenReturn("house-1")
        `when`(personal.contains("sharing")).thenReturn(true)
        `when`(personal.get("sharing")).thenReturn(ItemSharing.PERSONAL.name)

        `when`(snapshot.documents).thenReturn(listOf(shared, personal))
        listenerCaptor.value.onEvent(snapshot, null)

        assertEquals(1, emittedLists.size)
        assertEquals(listOf("item-1", "item-2"), emittedLists[0].map(Item::id))
        assertEquals("user-2", emittedLists[0][0].ownerId)
        assertEquals(date, emittedLists[0][0].expirationDate)
        assertEquals(ItemSharing.PERSONAL, emittedLists[0][1].sharing)
        verify(householdQuery).where(any(Filter::class.java))

        job.cancel()
      }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getFridgeItems_propagatesListenerError() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.whereEqualTo("ownerId", "user-1")).thenReturn(query)
        `when`(query.whereEqualTo("householdId", null)).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        `when`(query.addSnapshotListener(listenerCaptor.capture()))
            .thenReturn(mock(ListenerRegistration::class.java))

        var caughtError: Throwable? = null
        val job =
            launch(Dispatchers.Unconfined) {
              try {
                repository.getFridgeItems("user-1", null).collect {}
              } catch (exception: Exception) {
                caughtError = exception
              }
            }

        val exception =
            FirebaseFirestoreException("Error", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        listenerCaptor.value.onEvent(null, exception)

        assertTrue(caughtError is FirebaseFirestoreException)
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
    val doc = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
    `when`(doc.id).thenReturn("item-1")
    `when`(doc.getString("name")).thenReturn("Apple")
    `when`(doc.getString("ownerId")).thenReturn("user-1")
    `when`(doc.getString("householdId")).thenReturn("house-1")

    val date = Date()
    `when`(doc.getTimestamp("expirationDate")).thenReturn(Timestamp(date))
    `when`(doc.getString("category")).thenReturn("Dairy")
    `when`(doc.get("image"))
        .thenReturn(
            mapOf(
                "origin" to "REMOTE_IMAGE",
                "reference" to "https://images.example.org/apple.jpg",
                "author" to "Jane Doe",
                "sourceName" to "Photo library",
                "sourceUrl" to "https://example.org/photos/42",
                "title" to "Fresh apple",
                "license" to "by",
                "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
                "licenseVersion" to "4.0",
                "authorUrl" to "https://example.org/jane"))

    val item = doc.toItemOrNull()

    assertEquals("item-1", item?.id)
    assertEquals("Apple", item?.name)
    assertEquals("user-1", item?.ownerId)
    assertEquals("house-1", item?.householdId)
    assertEquals(date, item?.expirationDate)
    assertEquals("Dairy", item?.category)
    assertEquals(
        ItemImage(
            origin = ItemImageOrigin.REMOTE_IMAGE,
            reference = "https://images.example.org/apple.jpg",
            author = "Jane Doe",
            sourceName = "Photo library",
            sourceUrl = "https://example.org/photos/42",
            title = "Fresh apple",
            license = "by",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            licenseVersion = "4.0",
            authorUrl = "https://example.org/jane"),
        item?.image)
  }
}

/** Stubs the fields every stored item document must contain. */
internal fun DocumentSnapshot.stubRequiredItemFields(
    sharing: ItemSharing = ItemSharing.PERSONAL
): DocumentSnapshot = also {
  `when`(it.get("quantity")).thenReturn(1L)
  `when`(it.get("status")).thenReturn(ItemStatus.ACTIVE.name)
  `when`(it.get("sharing")).thenReturn(sharing.name)
}
