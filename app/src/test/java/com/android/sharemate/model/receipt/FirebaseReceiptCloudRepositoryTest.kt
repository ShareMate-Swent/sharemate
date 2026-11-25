// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.google.firebase.storage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseReceiptCloudRepositoryTest {
  @Test
  fun malformedBackupsDoNotHideValidEntriesOrStopLaterSnapshots() =
      runTest(dispatcher) {
        val invalid = document(receipt())
        `when`(invalid.id).thenReturn(UUID.randomUUID().toString())
        val malformed = document(receipt())
        `when`(malformed.getString("submission")).thenReturn("not json")
        val missing = mock(DocumentSnapshot::class.java)
        val events = mutableListOf<List<ReceiptCloudEntry>>()
        val job = backgroundScope.launch { repository.observe("owner", "home").toList(events) }
        runCurrent()
        emit(1, emptyList())
        emit(
            0,
            listOf(
                malformed,
                invalid,
                missing,
                document(receipt(owner = "peer")),
                document(receipt())))
        runCurrent()
        assertEquals(listOf(receipt()), events.last().map { it.submission })
        emit(0, emptyList())
        runCurrent()
        assertTrue(events.last().isEmpty())
        assertTrue(job.isActive)
        job.cancelAndJoin()
      }

  @Test
  fun cancellingObservationCleansPartialDownloadAndListeners() =
      runTest(dispatcher) {
        val download = mock(FileDownloadTask::class.java)
        lateinit var temporary: File
        lateinit var completed:
            com.google.android.gms.tasks.OnCompleteListener<FileDownloadTask.TaskSnapshot>
        `when`(
                download.addOnCompleteListener(
                    any(java.util.concurrent.Executor::class.java), any()))
            .thenAnswer {
              completed = it.getArgument(1)
              download
            }
        `when`(remote.getFile(any(File::class.java))).thenAnswer {
          temporary = it.getArgument(0)
          temporary.writeText("partial")
          download
        }
        val events = mutableListOf<List<ReceiptCloudEntry>>()
        val job = backgroundScope.launch { repository.observe("owner", "home").toList(events) }
        runCurrent()
        emit(1, emptyList())
        emit(0, listOf(document(receipt(), true)))
        runCurrent()
        assertEquals("", events.single().single().details.photoUri)
        verify(remote).getFile(any(File::class.java))
        job.cancelAndJoin()
        verify(download).cancel()
        temporary.writeText("late write after cancellation")
        completed.onComplete(download)
        assertFalse(temporary.exists())
        assertFalse(File(context.filesDir, "receipts/$id.jpg").exists())
        assertTrue(
            File(context.filesDir, "receipts").listFiles().orEmpty().none { it.extension == "tmp" })
        verify(registration, times(2)).remove()
      }

  @Test
  fun unavailablePhotoDirectoryDoesNotTerminateHistory() =
      runTest(dispatcher) {
        val files = java.nio.file.Files.createTempDirectory("receipt-test").toFile()
        val blocked = File(files, "receipts").also { it.writeText("not a directory") }
        val isolated = mock(Context::class.java)
        `when`(isolated.applicationContext).thenReturn(context)
        `when`(isolated.filesDir).thenReturn(files)
        val events = mutableListOf<List<ReceiptCloudEntry>>()
        val job =
            backgroundScope.launch {
              FirebaseReceiptCloudRepository(isolated, db, storage, dispatcher)
                  .observe("owner", "home")
                  .toList(events)
            }
        try {
          runCurrent()
          emit(1, emptyList())
          emit(0, listOf(document(receipt(), true)))
          runCurrent()
          advanceTimeBy(1_000)
          runCurrent()
          assertEquals(receipt(), events.last().single().submission)
          assertEquals("", events.last().single().details.photoUri)
          assertTrue(job.isActive)
        } finally {
          job.cancelAndJoin()
          blocked.delete()
          files.delete()
        }
      }

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = mock(FirebaseFirestore::class.java)
  private val collection = mock(CollectionReference::class.java)
  private val ref = mock(DocumentReference::class.java)
  private val query = mock(Query::class.java)
  private val tx = mock(Transaction::class.java)
  private val existing = mock(DocumentSnapshot::class.java)
  private val storage = mock(FirebaseStorage::class.java)
  private val remote = mock(StorageReference::class.java)
  private val registration = mock(ListenerRegistration::class.java)
  private val callbacks = mutableListOf<EventListener<QuerySnapshot>>()
  private val codec = AndroidInventorySubmissionStore(context, scheduleSync = false)
  private val dispatcher = StandardTestDispatcher()
  private val repository = FirebaseReceiptCloudRepository(context, db, storage, dispatcher)
  private val id = UUID.randomUUID().toString()

  private fun receipt(draft: Boolean = true, owner: String = "owner") =
      InventorySubmission(
          id,
          owner,
          "home",
          listOf(StagedItem("row", "Milk", 1, 100)),
          draft,
          if (draft) null else SharedExpense(id, "home", owner, 100, mapOf(owner to 100L)))

  @Before
  fun setUp() {
    `when`(db.collection(anyString())).thenReturn(collection)
    `when`(db.collectionGroup("receiptBackups")).thenReturn(query)
    `when`(collection.document(anyString())).thenReturn(ref)
    `when`(ref.collection(anyString())).thenReturn(collection)
    `when`(collection.whereEqualTo(anyString(), any())).thenReturn(query)
    `when`(query.whereEqualTo(anyString(), any())).thenReturn(query)
    `when`(tx.get(ref)).thenReturn(existing)
    `when`(db.runTransaction<Any?>(any())).thenAnswer {
      Tasks.forResult(it.getArgument<Transaction.Function<Any?>>(0).apply(tx))
    }
    `when`(storage.reference).thenReturn(remote)
    `when`(remote.child(anyString())).thenReturn(remote)
    `when`(query.addSnapshotListener(eq(MetadataChanges.INCLUDE), any())).thenAnswer {
      callbacks += it.getArgument<EventListener<QuerySnapshot>>(1)
      registration
    }
  }

  @Test
  fun uploadWaitsForExpenseAndNeverOverwritesSavedContent() =
      runTest(dispatcher) {
        val details = ReceiptDetails(id, "owner", "home", "", 5, title = "Groceries", revision = 3)
        repository.upload(ReceiptCloudEntry(receipt(false), details))
        verifyNoInteractions(tx)
        for ((exists, draft) in listOf(false to null, true to true, true to false, true to null)) {
          clearInvocations(tx)
          `when`(existing.exists()).thenReturn(exists)
          `when`(existing.getBoolean("draft")).thenReturn(draft)
          repository.upload(ReceiptCloudEntry(receipt(), details))
          if (!exists || draft == true)
              verify(tx)
                  .set(
                      eq(ref),
                      argThat<Map<String, Any?>> {
                        it["title"] == "Groceries" &&
                            it["revision"] == 3L &&
                            it["ownerId"] == "owner" &&
                            it["photoReady"] == false
                      })
          else verify(tx, never()).set(eq(ref), any())
        }
        verify(db, atLeastOnce()).collection("users")
        verify(ref, atLeastOnce()).collection("receiptBackups")
        verify(collection, atLeastOnce()).document("owner")
        val upload = mock(UploadTask::class.java)
        `when`(upload.isComplete).thenReturn(true)
        `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
        `when`(remote.putFile(any(Uri::class.java), any(StorageMetadata::class.java)))
            .thenReturn(upload)
        `when`(ref.update("photoReady", true)).thenReturn(Tasks.forResult(null))
        repository.upload(
            ReceiptCloudEntry(
                receipt(false).withProgress(true, ExpenseProgress.CONFIRMED),
                details.copy(photoUri = "file:///receipt.jpg")))
        verify(ref).update("photoReady", true)
      }

  private fun document(receipt: InventorySubmission, ready: Boolean = false): DocumentSnapshot =
      mock(DocumentSnapshot::class.java).also {
        `when`(it.id).thenReturn(receipt.id)
        `when`(it.getString("ownerId")).thenReturn(receipt.ownerId)
        `when`(it.getString("submission")).thenReturn(codec.encode(receipt).toString())
        `when`(it.getBoolean("photoReady")).thenReturn(ready)
      }

  private fun emit(
      index: Int,
      docs: List<DocumentSnapshot>,
      cached: Boolean = false,
      pending: Boolean = false
  ) {
    val snapshot = mock(QuerySnapshot::class.java)
    val metadata = mock(SnapshotMetadata::class.java)
    `when`(snapshot.documents).thenReturn(docs)
    `when`(snapshot.metadata).thenReturn(metadata)
    `when`(metadata.isFromCache).thenReturn(cached)
    `when`(metadata.hasPendingWrites()).thenReturn(pending)
    callbacks[index].onEvent(snapshot, null)
  }

  @Test
  fun observationsIgnoreUnconfirmedSnapshotsAndRemoveListenersOnCancellation() =
      runTest(dispatcher) {
        val events = mutableListOf<List<ReceiptCloudEntry>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
              repository.observe("owner", "home").toList(events)
            }
        runCurrent()
        callbacks[0].onEvent(null, null)
        emit(0, emptyList(), cached = true)
        emit(0, emptyList(), pending = true)
        emit(1, emptyList())
        runCurrent()
        assertTrue(events.isEmpty())
        val saved = document(receipt(false, "peer"))
        `when`(saved.getLong("scannedAtMillis")).thenReturn(42L)
        `when`(saved.getString("title")).thenReturn("Peer groceries")
        `when`(saved.getLong("revision")).thenReturn(7L)
        `when`(saved.get("draftParticipants")).thenReturn(listOf("owner", 12))
        emit(0, listOf(saved))
        emit(1, listOf(document(receipt())))
        runCurrent()
        assertEquals(2, events.last().size)
        assertEquals(setOf("owner"), events.last().first().details.draftParticipants)
        assertEquals(42L, events.last().first().details.savedAtMillis)
        assertEquals(7L, events.last().first().details.revision)
        assertEquals(0L, events.last().last().details.revision)
        assertNull(events.last().last().details.savedAtMillis)
        job.cancelAndJoin()
        verify(registration, times(2)).remove()
      }

  @Test
  fun photosAreCachedOnlyAfterACompleteDownloadAndFailuresCanRetry() =
      runTest(dispatcher) {
        val photo = File(context.filesDir, "receipts/$id.jpg")
        val download = mock(FileDownloadTask::class.java)
        `when`(download.isComplete).thenReturn(true)
        `when`(download.result).thenReturn(mock(FileDownloadTask.TaskSnapshot::class.java))
        var bytes = byteArrayOf()
        `when`(remote.getFile(any(File::class.java))).thenAnswer {
          it.getArgument<File>(0).writeBytes(bytes)
          download
        }
        val events = mutableListOf<List<ReceiptCloudEntry>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
              repository.observe("owner", "home").toList(events)
            }
        runCurrent()
        emit(1, emptyList())
        val doc = document(receipt(), true)
        emit(0, listOf(doc))
        runCurrent()
        assertFalse(photo.exists())
        `when`(download.exception).thenReturn(IOException("Offline"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("", events.last().single().details.photoUri)
        `when`(download.exception).thenReturn(null)
        bytes = byteArrayOf(1, 2, 3)
        advanceTimeBy(2_000)
        runCurrent()
        assertArrayEquals(bytes, photo.readBytes())
        assertEquals(Uri.fromFile(photo).toString(), events.last().single().details.photoUri)
        clearInvocations(remote)
        emit(0, listOf(doc))
        runCurrent()
        verify(remote, never()).getFile(any(File::class.java))
        job.cancelAndJoin()
        photo.delete()
      }

  @Test
  fun deletionsAndListenerErrorsPropagateWithoutLeakingSubscriptions() =
      runTest(dispatcher) {
        val result = async { repository.observeDeleted("owner", "home").first() }
        runCurrent()
        emit(0, listOf(document(receipt())))
        assertEquals(setOf(id), result.await())
        val error =
            FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        val failure = async { runCatching { repository.observeDeleted("owner", "home").first() } }
        runCurrent()
        callbacks.last().onEvent(null, error)
        val reported = failure.await().exceptionOrNull()
        assertTrue(reported is ReceiptCloudPermanentException)
        assertSame(error, reported?.cause)
        verify(registration, times(2)).remove()
      }
}
