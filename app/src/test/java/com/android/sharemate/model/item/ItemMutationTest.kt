// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ItemMutationTest {
  private lateinit var repository: FirebaseItemRepository
  private lateinit var document: DocumentReference

  @Before
  fun setUp() {
    val firestore = mock(FirebaseFirestore::class.java)
    val collection = mock(CollectionReference::class.java)
    document = mock(DocumentReference::class.java)
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    `when`(collection.document("item")).thenReturn(document)
    repository = FirebaseItemRepository(firestore)
  }

  @Test
  fun editOnlyWritesEditableFieldsIncludingExplicitNulls() = runBlocking {
    val fields =
        mapOf("name" to "Milk", "quantity" to 2, "category" to null, "expirationDate" to null)
    `when`(document.update(fields)).thenReturn(Tasks.forResult(null))
    val result = repository.updateItem("item", ItemEdit(" Milk ", 2, null, null))
    assertTrue((result as ItemWrite.Queued).confirmation.await().isSuccess)
    verify(document).update(fields)
    verifyNoMoreInteractions(document)
  }

  @Test
  fun submissionDoesNotWaitForServerAndLaterFailureIsObservable() = runBlocking {
    val pending = TaskCompletionSource<Void>()
    `when`(document.update("status", "EATEN")).thenReturn(pending.task)
    val result = repository.setItemStatus("item", ItemStatus.EATEN) as ItemWrite.Queued
    assertFalse(result.confirmation.isCompleted)
    val denied = IllegalStateException("Permission denied")
    pending.setException(denied)
    assertSame(denied, result.confirmation.await().exceptionOrNull())
  }

  @Test
  fun deletionReturnsBeforeAcknowledgementAndReportsCompletion() = runBlocking {
    val pending = TaskCompletionSource<Void>()
    `when`(document.delete()).thenReturn(pending.task)
    val result = repository.deleteItemQueued("item") as ItemWrite.Queued
    assertFalse(result.confirmation.isCompleted)
    pending.setResult(null)
    assertTrue(result.confirmation.await().isSuccess)
  }

  @Test
  fun cancellingConfirmationWaitDoesNotCancelSubmittedWrite() = runBlocking {
    val pending = TaskCompletionSource<Void>()
    `when`(document.delete()).thenReturn(pending.task)
    val result = repository.deleteItemQueued("item") as ItemWrite.Queued
    val waiter = async(start = CoroutineStart.UNDISPATCHED) { result.confirmation.await() }
    waiter.cancel()
    waiter.join()
    assertFalse(result.confirmation.isCancelled)
    pending.setResult(null)
    assertTrue(result.confirmation.await().isSuccess)
  }

  @Test
  fun editWritesReplacementCategoryAndExpiryWithoutReplacingDocument() = runBlocking {
    val expiry = Date(1_900_000_000_000)
    val fields =
        mapOf(
            "name" to "Apple", "quantity" to 3, "category" to "Fruits", "expirationDate" to expiry)
    `when`(document.update(fields)).thenReturn(Tasks.forResult(null))
    val result = repository.updateItem("item", ItemEdit("Apple", 3, "Fruits", expiry))
    assertTrue((result as ItemWrite.Queued).confirmation.await().isSuccess)
    verify(document).update(fields)
    verifyNoMoreInteractions(document)
  }

  @Test
  fun invalidEditsAndIdsAreRejectedBeforeWriting() {
    for (quantity in listOf(0, -1, Int.MIN_VALUE)) {
      assertTrue(
          repository.updateItem("item", ItemEdit("Milk", quantity, null, null))
              is ItemWrite.Rejected)
    }
    assertTrue(repository.updateItem("item", ItemEdit(" ", 1, null, null)) is ItemWrite.Rejected)
    for (id in listOf("", " ", "collection/id")) {
      assertTrue(repository.updateItem(id, ItemEdit("Milk", 1, null, null)) is ItemWrite.Rejected)
      assertTrue(repository.setItemStatus(id, ItemStatus.ACTIVE) is ItemWrite.Rejected)
      assertTrue(repository.deleteItemQueued(id) is ItemWrite.Rejected)
    }
    verifyNoInteractions(document)
  }

  @Test
  fun immediateAndSynchronousWriteFailuresAreRejected() {
    val failure = IllegalStateException("Missing item")
    `when`(document.update("status", "ACTIVE")).thenReturn(Tasks.forException(failure))
    assertSame(
        failure, (repository.setItemStatus("item", ItemStatus.ACTIVE) as ItemWrite.Rejected).cause)
    `when`(document.delete()).thenThrow(failure)
    assertSame(failure, (repository.deleteItemQueued("item") as ItemWrite.Rejected).cause)
  }

  @Test
  fun mapperPreservesAllFieldsAndDefaultsOnlyMissingNewFields() {
    val doc = validDocument()
    val date = Date(1234)
    `when`(doc.getTimestamp("expirationDate")).thenReturn(com.google.firebase.Timestamp(date))
    `when`(doc.getString("category")).thenReturn("Dairy")
    val legacy = checkNotNull(doc.toItemOrNull())
    assertEquals(1, legacy.quantity)
    assertEquals(ItemStatus.ACTIVE, legacy.status)
    assertEquals("Dairy", legacy.category)
    assertEquals(date, legacy.expirationDate)
    `when`(doc.contains("quantity")).thenReturn(true)
    `when`(doc.get("quantity")).thenReturn(5L)
    `when`(doc.contains("status")).thenReturn(true)
    for (status in ItemStatus.values()) {
      `when`(doc.get("status")).thenReturn(status.name)
      assertEquals(legacy.copy(quantity = 5, status = status), doc.toItemOrNull())
    }
  }

  @Test
  fun mapperDropsMalformedDocumentsInsteadOfCrashingReadFlow() {
    for (quantity in listOf(null, 0L, -1L, 1.5, "2", Long.MAX_VALUE)) {
      val doc = validDocument()
      `when`(doc.contains("quantity")).thenReturn(true)
      `when`(doc.get("quantity")).thenReturn(quantity)
      assertNull(doc.toItemOrNull())
    }
    for (status in listOf(null, "UNKNOWN", "eaten", 2L)) {
      val doc = validDocument()
      `when`(doc.contains("status")).thenReturn(true)
      `when`(doc.get("status")).thenReturn(status)
      assertNull(doc.toItemOrNull())
    }
    val doc = validDocument()
    `when`(doc.getString("category")).thenThrow(RuntimeException("Wrong type"))
    assertNull(doc.toItemOrNull())
  }

  @Test
  fun fakeEditsTransitionsAndDeletesPreservePrivateSharedSeparation() = runBlocking {
    val fake = FakeItemRepository()
    val privateItem = Item("private", "Milk", "owner", category = "Dairy")
    val sharedItem = Item("shared", "Bread", "owner", "home")
    fake.emitItems(listOf(privateItem, sharedItem))
    assertTrue(fake.updateItem("private", ItemEdit(" Milk ", 4, null, null)) is ItemWrite.Queued)
    for (status in ItemStatus.values()) {
      assertTrue(fake.setItemStatus("private", status) is ItemWrite.Queued)
      assertEquals(
          privateItem.copy(quantity = 4, category = null, status = status),
          fake.getPrivateItems("owner").first().single())
      assertEquals(listOf(sharedItem), fake.getSharedItems("home").first())
    }
    assertTrue(fake.updateItem("missing", ItemEdit("Milk", 1, null, null)) is ItemWrite.Rejected)
    assertTrue(fake.setItemStatus("missing", ItemStatus.EATEN) is ItemWrite.Rejected)
    assertTrue(fake.updateItem("private", ItemEdit("Milk", 0, null, null)) is ItemWrite.Rejected)
    assertNull(fake.addItem(privateItem.copy(quantity = -1)))
    assertTrue(fake.deleteItemQueued("private") is ItemWrite.Queued)
    assertTrue(fake.getPrivateItems("owner").first().isEmpty())
    assertTrue(fake.deleteItemQueued("private") is ItemWrite.Queued)
    assertEquals(listOf(sharedItem), fake.getSharedItems("home").first())
  }

  private fun validDocument(): DocumentSnapshot =
      mock(DocumentSnapshot::class.java).also {
        `when`(it.id).thenReturn("item")
        `when`(it.getString("name")).thenReturn("Milk")
        `when`(it.getString("ownerId")).thenReturn("owner")
      }
}
