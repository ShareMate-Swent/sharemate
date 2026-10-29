// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.util.CustomClassMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class ItemSharingTest {
  private lateinit var repository: FirebaseItemRepository
  private lateinit var collection: CollectionReference
  private lateinit var document: DocumentReference

  @Before
  fun setUp() {
    val firestore = mock(FirebaseFirestore::class.java)
    collection = mock(CollectionReference::class.java)
    document = mock(DocumentReference::class.java)
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    `when`(collection.document("item")).thenReturn(document)
    `when`(collection.document()).thenReturn(document)
    `when`(document.id).thenReturn("item")
    repository = FirebaseItemRepository(firestore)
  }

  @Test
  fun legacyHouseholdItemsDefaultToShared() {
    assertEquals(ItemSharing.SHARED, Item("item", "Milk", "owner", "home").sharing)
    assertEquals(ItemSharing.SHARED, snapshot("home").toItemOrNull()!!.sharing)
    assertTrue(snapshot("home").toItemOrNull()!!.isShared)
  }

  @Test
  fun standaloneItemsRemainOwnerOnlyRegardlessOfSharingLabel() {
    val legacy = snapshot(null).toItemOrNull()!!
    assertEquals(ItemSharing.PERSONAL, legacy.sharing)
    assertFalse(legacy.isShared)
    assertFalse(legacy.copy(sharing = ItemSharing.SHARED).isShared)
  }

  @Test
  fun personalHouseholdItemsRetainTheirFridgeAndOwnerAcrossTransitions() = runBlocking {
    val fake = FakeItemRepository()
    val original = Item("item", "Milk", "owner", "home")
    fake.emitItems(listOf(original))
    for (sharing in listOf(ItemSharing.PERSONAL, ItemSharing.SHARED)) {
      val write = fake.setItemSharing("item", sharing) as ItemWrite.Queued
      assertTrue(write.confirmation.await().isSuccess)
      assertEquals(original.copy(sharing = sharing), fake.getSharedItems("home").first().single())
      assertTrue(fake.getPrivateItems("owner").first().isEmpty())
    }
  }

  @Test
  fun ordinaryMutationsPreservePersonalSharingAndStandalonePrivacy() = runBlocking {
    val fake = FakeItemRepository()
    val personal = Item("personal", "Milk", "owner", "home", sharing = ItemSharing.PERSONAL)
    val standalone = Item("standalone", "Bread", "owner")
    fake.emitItems(listOf(personal, standalone))
    assertTrue(
        fake.updateItem(personal.id, ItemEdit("Oat milk", 2, null, null)) is ItemWrite.Queued)
    assertTrue(fake.setItemStatus(personal.id, ItemStatus.EATEN) is ItemWrite.Queued)
    assertEquals(
        personal.copy(name = "Oat milk", quantity = 2, status = ItemStatus.EATEN),
        fake.getSharedItems("home").first().single())
    assertEquals(listOf(standalone), fake.getPrivateItems("owner").first())
    assertTrue(fake.setItemSharing(standalone.id, ItemSharing.SHARED) is ItemWrite.Queued)
    assertEquals(
        listOf(standalone.copy(sharing = ItemSharing.SHARED)),
        fake.getPrivateItems("owner").first())
    assertEquals(1, fake.getSharedItems("home").first().size)
    assertTrue(fake.deleteItemQueued(personal.id) is ItemWrite.Queued)
    assertTrue(fake.getSharedItems("home").first().isEmpty())
  }

  @Test
  fun creationSubmitsSelectedSharingAndOriginalAssociations() = runBlocking {
    `when`(document.set(any(Item::class.java))).thenReturn(Tasks.forResult(null))
    for (sharing in ItemSharing.values()) {
      val item = Item(name = "Milk", ownerId = "owner", householdId = "home", sharing = sharing)
      assertEquals("item", repository.addItem(item))
    }
    val captor = ArgumentCaptor.forClass(Item::class.java)
    verify(document, times(2)).set(captor.capture())
    assertEquals(ItemSharing.values().toList(), captor.allValues.map { it.sharing })
    assertTrue(captor.allValues.all { it.ownerId == "owner" && it.householdId == "home" })
  }

  @Test
  fun firestoreSerializationPersistsSharingWithoutDerivedBoolean() {
    for (householdId in listOf(null, "home")) {
      for (sharing in ItemSharing.values()) {
        val item =
            Item(name = "Milk", ownerId = "owner", householdId = householdId, sharing = sharing)
        val fields = CustomClassMapper.convertToPlainJavaTypes(item) as Map<*, *>
        assertEquals(sharing.name, fields["sharing"])
        assertFalse(fields.containsKey("shared"))
        assertFalse(fields.containsKey("isShared"))
        assertEquals("owner", fields["ownerId"])
        assertEquals(householdId, fields["householdId"])
        assertEquals(householdId != null && sharing == ItemSharing.SHARED, item.isShared)
      }
    }
  }

  @Test
  fun legacyDefaultsIgnoreStoredBooleanWithoutMigration() {
    for (householdId in listOf(null, "home")) {
      for (legacyBoolean in listOf(false, true)) {
        val doc = snapshot(householdId)
        `when`(doc.contains("shared")).thenReturn(true)
        `when`(doc.get("shared")).thenReturn(legacyBoolean)
        `when`(doc.getBoolean("shared")).thenReturn(legacyBoolean)
        val item = doc.toItemOrNull()!!
        assertEquals(
            if (householdId == null) ItemSharing.PERSONAL else ItemSharing.SHARED, item.sharing)
        assertEquals("owner", item.ownerId)
        assertEquals(householdId, item.householdId)
      }
    }
  }

  @Test
  fun mapperRestoresBothSharingValuesWithoutChangingAssociations() {
    for (sharing in ItemSharing.values()) {
      val doc = snapshot("home")
      `when`(doc.contains("sharing")).thenReturn(true)
      `when`(doc.get("sharing")).thenReturn(sharing.name)
      val contradictoryBoolean = sharing != ItemSharing.SHARED
      `when`(doc.contains("shared")).thenReturn(true)
      `when`(doc.get("shared")).thenReturn(contradictoryBoolean)
      `when`(doc.getBoolean("shared")).thenReturn(contradictoryBoolean)
      val item = doc.toItemOrNull()!!
      assertEquals(sharing, item.sharing)
      assertEquals("owner", item.ownerId)
      assertEquals("home", item.householdId)
      assertEquals(sharing == ItemSharing.SHARED, item.isShared)
    }
  }

  @Test
  fun malformedSharingDoesNotSilentlyBecomeShared() {
    for (value in listOf(null, "UNKNOWN", "personal", 1L, true)) {
      val doc = snapshot("home")
      `when`(doc.contains("sharing")).thenReturn(true)
      `when`(doc.get("sharing")).thenReturn(value)
      assertNull(doc.toItemOrNull())
    }
  }

  @Test
  fun sharingUpdatesAtomicallyRemoveLegacyBooleanAndReuseServerConfirmation() = runBlocking {
    for (sharing in ItemSharing.values()) {
      val pending = TaskCompletionSource<Void>()
      `when`(document.update(sharingUpdate(sharing))).thenReturn(pending.task)
      val write = repository.setItemSharing("item", sharing) as ItemWrite.Queued
      assertFalse(write.confirmation.isCompleted)
      pending.setResult(null)
      assertTrue(write.confirmation.await().isSuccess)
      verify(document).update(sharingUpdate(sharing))
    }
    verifyNoMoreInteractions(document)
  }

  @Test
  fun unauthorizedServerConfirmationIsReported() = runBlocking {
    val pending = TaskCompletionSource<Void>()
    `when`(document.update(sharingUpdate(ItemSharing.PERSONAL))).thenReturn(pending.task)
    val write = repository.setItemSharing("item", ItemSharing.PERSONAL) as ItemWrite.Queued
    val denied = IllegalStateException("Permission denied")
    pending.setException(denied)
    assertSame(denied, write.confirmation.await().exceptionOrNull())
  }

  @Test
  fun invalidIdsAndImmediateFailuresAreRejected() {
    for (id in listOf("", " ", "items/id")) {
      assertTrue(repository.setItemSharing(id, ItemSharing.PERSONAL) is ItemWrite.Rejected)
    }
    verifyNoInteractions(document)
    val denied = IllegalStateException("Permission denied")
    `when`(document.update(sharingUpdate(ItemSharing.PERSONAL)))
        .thenReturn(Tasks.forException(denied))
    assertSame(
        denied,
        (repository.setItemSharing("item", ItemSharing.PERSONAL) as ItemWrite.Rejected).cause)
    `when`(document.update(sharingUpdate(ItemSharing.PERSONAL))).thenThrow(denied)
    assertSame(
        denied,
        (repository.setItemSharing("item", ItemSharing.PERSONAL) as ItemWrite.Rejected).cause)
  }

  private fun sharingUpdate(sharing: ItemSharing): Map<String, Any> =
      mapOf("sharing" to sharing.name, "shared" to FieldValue.delete())

  private fun snapshot(householdId: String?): DocumentSnapshot =
      mock(DocumentSnapshot::class.java).also {
        `when`(it.id).thenReturn("item")
        `when`(it.getString("name")).thenReturn("Milk")
        `when`(it.getString("ownerId")).thenReturn("owner")
        `when`(it.getString("householdId")).thenReturn(householdId)
      }
}
