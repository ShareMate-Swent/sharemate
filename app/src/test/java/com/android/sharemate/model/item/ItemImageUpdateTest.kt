// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ItemImageUpdateTest {
  private lateinit var firestore: FirebaseFirestore
  private lateinit var document: DocumentReference
  private lateinit var transaction: Transaction
  private lateinit var snapshot: DocumentSnapshot
  private lateinit var repository: FirebaseItemRepository
  private val image =
      ItemImage(
          origin = ItemImageOrigin.REMOTE_IMAGE,
          reference = "https://images.example.org/milk.jpg",
          author = "Jane Doe",
          sourceName = "Photo library",
          sourceUrl = "https://example.org/photos/42",
          title = "Fresh milk",
          license = "by",
          licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
          licenseVersion = "4.0",
          authorUrl = "https://example.org/jane")

  @Before
  fun setUp() {
    firestore = mock(FirebaseFirestore::class.java)
    val collection = mock(CollectionReference::class.java)
    document = mock(DocumentReference::class.java)
    transaction = mock(Transaction::class.java)
    snapshot = mock(DocumentSnapshot::class.java)
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    `when`(collection.document("item")).thenReturn(document)
    doReturn(snapshot).`when`(transaction).get(document)
    `when`(snapshot.exists()).thenReturn(true)
    `when`(snapshot.getString("name")).thenReturn("Milk")
    `when`(firestore.runTransaction<ItemImageUpdateResult>(any())).thenAnswer {
      val callback = it.getArgument<Transaction.Function<ItemImageUpdateResult>>(0)
      Tasks.forResult(callback.apply(transaction))
    }
    repository = FirebaseItemRepository(firestore)
  }

  @Test
  fun attachesImageAndAllCreditsUsingOnlyATransactionalFieldUpdate() = runBlocking {
    assertSame(
        ItemImageUpdateResult.Updated, repository.updateItemImageIfAbsent("item", "Milk", image))
    verify(transaction).get(document)
    verify(transaction).update(document, "image", image)
    verifyNoMoreInteractions(transaction)
    verifyNoInteractions(document)
  }

  @Test
  fun deletedItemIsIgnoredWithoutRecreatingIt() = runBlocking {
    `when`(snapshot.exists()).thenReturn(false)
    assertIgnoredWithoutWriting()
  }

  @Test
  fun nameMustStillMatchExactly() = runBlocking {
    for (name in listOf("Oat milk", "milk", " Milk ", null)) {
      `when`(snapshot.getString("name")).thenReturn(name)
      assertIgnoredWithoutWriting()
    }
  }

  @Test
  fun anyStoredImageIsPreservedIncludingPersonalAndMalformedImages() = runBlocking {
    for (stored in
        listOf(
            mapOf("origin" to "PERSONAL_PHOTO", "reference" to "content://personal"),
            mapOf("origin" to "REMOTE_IMAGE", "reference" to image.reference),
            emptyMap<String, String>(),
            "legacy-image")) {
      `when`(snapshot.get("image")).thenReturn(stored)
      assertIgnoredWithoutWriting()
    }
  }

  @Test
  fun transactionRetryRechecksDeletionRenameAndNewImage() = runBlocking {
    val changed = mock(DocumentSnapshot::class.java)
    val retry = mock(Transaction::class.java)
    `when`(retry.get(document)).thenReturn(changed)
    doAnswer {
          val callback = it.getArgument<Transaction.Function<ItemImageUpdateResult>>(0)
          assertSame(ItemImageUpdateResult.Updated, callback.apply(transaction))
          Tasks.forResult(callback.apply(retry))
        }
        .`when`(firestore)
        .runTransaction<ItemImageUpdateResult>(any())

    for (change in listOf("deleted", "renamed", "image")) {
      `when`(changed.exists()).thenReturn(change != "deleted")
      `when`(changed.getString("name")).thenReturn(if (change == "renamed") "Oat milk" else "Milk")
      `when`(changed.get("image"))
          .thenReturn(if (change == "image") mapOf("reference" to "new") else null)
      assertSame(
          ItemImageUpdateResult.Ignored, repository.updateItemImageIfAbsent("item", "Milk", image))
    }
    verify(retry, times(3)).get(document)
    verifyNoMoreInteractions(retry)
  }

  @Test
  fun waitsForCommitAndReportsWriteFailureInsteadOfSuccess() = runBlocking {
    val pending = TaskCompletionSource<ItemImageUpdateResult>()
    doAnswer {
          val callback = it.getArgument<Transaction.Function<ItemImageUpdateResult>>(0)
          assertSame(ItemImageUpdateResult.Updated, callback.apply(transaction))
          pending.task
        }
        .`when`(firestore)
        .runTransaction<ItemImageUpdateResult>(any())
    val result =
        async(start = CoroutineStart.UNDISPATCHED) {
          repository.updateItemImageIfAbsent("item", "Milk", image)
        }
    assertFalse(result.isCompleted)
    val failure = IllegalStateException("Write denied")
    pending.setException(failure)
    val reported = (result.await() as ItemImageUpdateResult.Error).cause
    assertEquals(failure.javaClass, reported.javaClass)
    assertEquals(failure.message, reported.message)
  }

  @Test
  fun synchronousReadAndWriteFailuresAreErrors() = runBlocking {
    val readFailure = IllegalStateException("Read failed")
    `when`(transaction.get(document)).thenThrow(readFailure)
    assertSame(
        readFailure,
        (repository.updateItemImageIfAbsent("item", "Milk", image) as ItemImageUpdateResult.Error)
            .cause)
    doReturn(snapshot).`when`(transaction).get(document)
    val writeFailure = IllegalStateException("Write failed")
    `when`(transaction.update(document, "image", image)).thenThrow(writeFailure)
    assertSame(
        writeFailure,
        (repository.updateItemImageIfAbsent("item", "Milk", image) as ItemImageUpdateResult.Error)
            .cause)
  }

  @Test
  fun cancellationPropagates() = runBlocking {
    val cancelled = CancellationException("Cancelled")
    doThrow(cancelled).`when`(firestore).runTransaction<ItemImageUpdateResult>(any())
    try {
      repository.updateItemImageIfAbsent("item", "Milk", image)
      fail("Cancellation must propagate")
    } catch (actual: CancellationException) {
      assertSame(cancelled, actual)
    }
  }

  @Test
  fun invalidIdsFailBeforeAccessingFirestore() =
      runBlocking<Unit> {
        val fake = FakeItemRepository()
        for (id in listOf("", " ", "items/item")) {
          assertTrue(
              repository.updateItemImageIfAbsent(id, "Milk", image) is ItemImageUpdateResult.Error)
          assertTrue(fake.updateItemImageIfAbsent(id, "Milk", image) is ItemImageUpdateResult.Error)
        }
        verifyNoInteractions(document, transaction)
        verify(firestore, never()).runTransaction<ItemImageUpdateResult>(any())
      }

  @Test
  fun fakeUpdatesOnlyImageAndPreservesOtherItemsAndFields() = runBlocking {
    val fake = FakeItemRepository()
    val item =
        Item(
            "item",
            "Milk",
            "owner",
            "home",
            Date(1234),
            "Dairy",
            quantity = 4,
            status = ItemStatus.EATEN)
    val other = Item("other", "Milk", "owner", "home")
    fake.emitItems(listOf(item, other))
    assertSame(ItemImageUpdateResult.Updated, fake.updateItemImageIfAbsent("item", "Milk", image))
    assertEquals(listOf(item.copy(image = image), other), fake.getSharedItems("home").first())
  }

  @Test
  fun fakeIgnoresMissingRenamedAndAlreadyIllustratedItems() = runBlocking {
    val fake = FakeItemRepository()
    val personal = ItemImage(reference = "content://personal")
    val items =
        listOf(
            Item("renamed", "Oat milk", "owner"),
            Item("personal", "Milk", "owner", image = personal),
            Item("remote", "Milk", "owner", image = image))
    fake.emitItems(items)
    for (id in listOf("missing", "renamed", "personal", "remote")) {
      assertSame(ItemImageUpdateResult.Ignored, fake.updateItemImageIfAbsent(id, "Milk", image))
    }
    assertEquals(items, fake.getPrivateItems("owner").first())
  }

  @Test
  fun fakeWriteFailureLeavesItemUnchanged() = runBlocking {
    val fake = FakeItemRepository()
    val item = Item("item", "Milk", "owner")
    fake.emitItems(listOf(item))
    val failure = IllegalStateException("Write failed")
    fake.imageUpdateFailure = failure
    assertSame(
        failure,
        (fake.updateItemImageIfAbsent("item", "Milk", image) as ItemImageUpdateResult.Error).cause)
    assertEquals(listOf(item), fake.getPrivateItems("owner").first())
  }

  private suspend fun assertIgnoredWithoutWriting() {
    assertSame(
        ItemImageUpdateResult.Ignored, repository.updateItemImageIfAbsent("item", "Milk", image))
    verify(transaction, never()).update(document, "image", image)
    verifyNoInteractions(document)
  }
}
