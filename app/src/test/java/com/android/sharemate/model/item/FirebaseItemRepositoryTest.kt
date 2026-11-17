// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: AI Assistant
// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.text.TextLimits
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class FirebaseItemRepositoryTest {

  @Test
  fun inventoryReadsStoredImageAndAttribution() {
    val doc = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
    `when`(doc.id).thenReturn("milk")
    `when`(doc.getString("name")).thenReturn("Milk")
    `when`(doc.getString("ownerId")).thenReturn("owner")
    `when`(doc.getString("householdId")).thenReturn("house-1")
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
    `when`(doc.getString("householdId")).thenReturn("house-1")
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

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getFridgeItems_handlesError() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.where(fridgeFilter("house-1", "user-1"))).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        var caughtError: Throwable? = null
        val job =
            launch(Dispatchers.Unconfined) {
              try {
                repository.getFridgeItems("house-1", "user-1").collect {}
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
  fun getFridgeItems_returnsEmptyFlowWithoutQuery_whenHouseholdOrUserIsBlank() =
      runBlocking<Unit> {
        for ((household, user) in
            listOf("" to "user-1", "  " to "user-1", "house-1" to "", "house-1" to " ")) {
          assertTrue(repository.getFridgeItems(household, user).first().isEmpty())
        }
        verify(collection, never()).where(any(Filter::class.java))
      }

  @Test
  fun getFridgeItems_asksForTheHouseholdsSharedItemsAndTheUsersOwnItemsOnly() {
    // Stubbing with the exact filter proves its shape: any other query returns null and fails.
    // The security rules reject a query that could return the personal items of other members.
    val expected =
        Filter.and(
            Filter.equalTo("householdId", "house-1"),
            Filter.or(Filter.equalTo("sharing", "SHARED"), Filter.equalTo("ownerId", "user-1")))
    assertEquals(expected, fridgeFilter("house-1", "user-1"))
    assertNotEquals(expected, fridgeFilter("house-1", "user-2"))
    assertNotEquals(expected, fridgeFilter("house-2", "user-1"))
    val query = mock(Query::class.java)
    `when`(collection.where(expected)).thenReturn(query)
    `when`(query.addSnapshotListener(any())).thenReturn(mock(ListenerRegistration::class.java))

    runBlocking {
      val job =
          launch(Dispatchers.Unconfined) {
            repository.getFridgeItems("house-1", "user-1").collect {}
          }
      verify(query).addSnapshotListener(any())
      job.cancel()
    }
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getFridgeItems_emitsItems() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.where(fridgeFilter("house-1", "user-1"))).thenReturn(query)

        val listenerCaptor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(listenerCaptor.capture())).thenReturn(registration)

        val emittedLists = mutableListOf<List<Item>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.getFridgeItems("house-1", "user-1").collect { emittedLists.add(it) }
            }

        val snapshot = mock(QuerySnapshot::class.java)
        val doc = mock(DocumentSnapshot::class.java).stubRequiredItemFields()
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
        assertNull(
            repository.addItem(Item(name = "  ", ownerId = "user-1", householdId = "house-1")))
        assertNull(repository.addItem(Item(name = "Apple", ownerId = "", householdId = "house-1")))
      }

  @Test
  fun addItem_succeedsAndTrimsData() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenReturn(Tasks.forResult(null))

        val result =
            repository.addItem(
                Item(name = "  Banana  ", ownerId = "user-1", householdId = "house-1"))

        assertEquals("generated-id", result)

        val captor = ArgumentCaptor.forClass(Item::class.java)
        verify(document).set(captor.capture())

        val savedItem = captor.value
        assertEquals("generated-id", savedItem.id)
        assertEquals("Banana", savedItem.name)
        assertEquals("user-1", savedItem.ownerId)
        assertEquals("house-1", savedItem.householdId)
        assertEquals(ItemSharing.PERSONAL, savedItem.sharing)
      }

  @Test
  fun addItem_rethrowsCancellationException() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenThrow(CancellationException("Cancelled"))

        try {
          repository.addItem(Item(name = "Apple", ownerId = "user-1", householdId = "house-1"))
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

        val result =
            repository.addItem(Item(name = "Apple", ownerId = "user-1", householdId = "house-1"))
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

  /** The query a member's fridge uses: the household, and shared items or the user's own. */
  private fun fridgeFilter(householdId: String, userId: String): Filter =
      Filter.and(
          Filter.equalTo("householdId", householdId),
          Filter.or(Filter.equalTo("sharing", "SHARED"), Filter.equalTo("ownerId", userId)))

  @Test
  fun addItem_refusesTextsOverTheLimitsWithoutWriting() =
      runBlocking<Unit> {
        val tooLongName = "n".repeat(TextLimits.ITEM_NAME + 1)
        val tooLongCategory = "c".repeat(TextLimits.ITEM_CATEGORY + 1)
        assertNull(repository.addItem(Item(name = tooLongName, ownerId = "u", householdId = "h")))
        assertNull(
            repository.addItem(
                Item(
                    name = "😀".repeat(TextLimits.ITEM_NAME + 1),
                    ownerId = "u",
                    householdId = "h")))
        assertNull(
            repository.addItem(
                Item(name = "Milk", category = tooLongCategory, ownerId = "u", householdId = "h")))
        verifyNoInteractions(collection)
      }

  @Test
  fun addItem_refusesAQuantityOutsideTheRangeWithoutWriting() =
      runBlocking<Unit> {
        for (quantity in listOf(0, -1, ItemQuantity.MAX + 1, Int.MAX_VALUE)) {
          assertNull(
              repository.addItem(
                  Item(name = "Milk", ownerId = "u", householdId = "h", quantity = quantity)))
        }
        verifyNoInteractions(collection)
      }

  @Test
  fun addItem_acceptsTextsAtTheLimitsAfterTrimming() =
      runBlocking<Unit> {
        val document = mock(DocumentReference::class.java)
        `when`(collection.document()).thenReturn(document)
        `when`(document.id).thenReturn("generated-id")
        `when`(document.set(any(Item::class.java))).thenReturn(Tasks.forResult(null))
        val name = "n".repeat(TextLimits.ITEM_NAME)
        val category = "c".repeat(TextLimits.ITEM_CATEGORY)

        val id =
            repository.addItem(
                Item(name = " $name ", category = category, ownerId = "u", householdId = "h"))

        assertEquals("generated-id", id)
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
