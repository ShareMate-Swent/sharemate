// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.incident

import com.android.sharemate.model.image.PersonalPhotoRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

/** Tests transfers, session validation and cancellation with mocked Storage and Auth. */
@RunWith(RobolectricTestRunner::class)
class FirebaseIncidentPhotoRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val storage = mock(FirebaseStorage::class.java)
  private val auth = mock(FirebaseAuth::class.java)
  private val user = mock(FirebaseUser::class.java)
  private val root = mock(StorageReference::class.java)
  private val remote = mock(StorageReference::class.java)
  private val upload = mock(UploadTask::class.java)
  private val download = mock(StreamDownloadTask::class.java)
  private var sequence = 0L
  private val repository = FirebaseIncidentPhotoRepository(storage, auth) { UUID(0, ++sequence) }
  private val reference = IncidentPhotoReference.forUpload("home", "alice", UUID(0, 1))
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42)
  private val jpeg = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 42)

  @Before
  fun setUp() {
    `when`(auth.currentUser).thenReturn(user)
    `when`(user.uid).thenReturn("alice")
    `when`(storage.reference).thenReturn(root)
    `when`(root.child(anyString())).thenReturn(remote)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenReturn(upload)
    `when`(upload.isComplete).thenReturn(true)
    `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
    `when`(download.isComplete).thenReturn(true)
    `when`(download.result).thenReturn(mock(StreamDownloadTask.TaskSnapshot::class.java))
    serve(png)
  }

  private fun photo(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

  private fun serve(bytes: ByteArray, afterRead: () -> Unit = {}) {
    doAnswer {
          val stream = spy(bytes.inputStream())
          try {
            it.getArgument<StreamDownloadTask.StreamProcessor>(0)
                .doInBackground(download.result, stream)
          } finally {
            verify(stream).close()
          }
          afterRead()
          download
        }
        .`when`(remote)
        .getStream(any(StreamDownloadTask.StreamProcessor::class.java))
  }

  @Test
  fun jpegAndPngUploadsPreserveSourceAndUseFreshPrivateReferences() = runTest {
    val references = mutableListOf<IncidentPhotoReference>()
    for (bytes in listOf(png, jpeg)) {
      val source = photo(bytes)
      references += repository.upload("home", "alice", source).getOrThrow()
      assertArrayEquals(bytes, source.readBytes())
    }
    assertNotEquals(references[0], references[1])
    references.forEach { verify(root).child(it.path) }
    val metadata = ArgumentCaptor.forClass(StorageMetadata::class.java)
    verify(remote, times(2)).putBytes(any(ByteArray::class.java), metadata.capture())
    assertEquals(listOf("image/png", "image/jpeg"), metadata.allValues.map { it.contentType })
    assertArrayEquals(png, repository.load(IncidentPhotoReference(references[0].path)).getOrThrow())
    verify(remote, never()).downloadUrl
    verify(remote, never()).delete()
  }

  @Test
  fun emptyInvalidOversizedAndMissingSourcesFailWithoutTransfer() = runTest {
    for (bytes in
        listOf(
            byteArrayOf(),
            "bad".toByteArray(),
            png.copyOf(PersonalPhotoRepository.MAX_BYTES + 1))) {
      val source = photo(bytes)
      assertTrue(
          repository.upload("home", "alice", source).exceptionOrNull() is IllegalArgumentException)
      assertArrayEquals(bytes, source.readBytes())
    }
    assertTrue(repository.upload("home", "alice", File(temporary.root, "missing")).isFailure)
    verifyNoInteractions(root)
  }

  @Test
  fun exactLimitIsAcceptedAndInvalidHouseholdFailsBeforeTransfer() = runTest {
    val source = photo(png.copyOf(PersonalPhotoRepository.MAX_BYTES))
    assertTrue(repository.upload("home", "alice", source).isSuccess)
    assertTrue(repository.upload("../other", "alice", source).isFailure)
    verify(remote).putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java))
  }

  @Test
  fun missingSessionOrForgedAuthorFailsBeforeStorageAccess() = runTest {
    val source = photo(png)
    val forged = repository.upload("home", "bob", source).exceptionOrNull()
    assertTrue(forged is IllegalStateException)
    assertEquals("Unexpected photo author", forged?.message)
    `when`(auth.currentUser).thenReturn(null)
    val signedOut = repository.upload("home", "alice", source).exceptionOrNull()
    assertTrue(signedOut is IllegalStateException)
    assertEquals("No signed-in user", signedOut?.message)
    assertEquals("No signed-in user", repository.load(reference).exceptionOrNull()?.message)
    verifyNoInteractions(root)
  }

  @Test
  fun uploadAndDownloadFailuresAreReturnedExplicitly() = runTest {
    val failure = IOException("Transfer failed")
    `when`(upload.exception).thenReturn(failure)
    assertSame(failure, repository.upload("home", "alice", photo(png)).exceptionOrNull())
    `when`(download.exception).thenReturn(failure)
    assertSame(failure, repository.load(reference).exceptionOrNull())
  }

  @Test
  fun invalidAndOversizedDownloadsAreRejectedAndStreamsAreClosed() = runTest {
    for (bytes in
        listOf(
            byteArrayOf(),
            "not a photo".toByteArray(),
            png.copyOf(PersonalPhotoRepository.MAX_BYTES + 1))) {
      serve(bytes)
      assertTrue(repository.load(reference).exceptionOrNull() is IllegalArgumentException)
    }
  }

  @Test
  fun sessionChangeDuringLocalReadPreventsUploadForThePreviousUser() = runTest {
    val source = photo(png.copyOf(DEFAULT_BUFFER_SIZE * 2))
    val original = source.readBytes()
    val bob = mock(FirebaseUser::class.java)
    `when`(bob.uid).thenReturn("bob")
    // Capture Alice's session, allow the first read chunk, then switch before the next chunk.
    `when`(auth.currentUser).thenReturn(user, user, bob)

    val result = repository.upload("home", "alice", source)

    assertTrue(result.exceptionOrNull() is IllegalStateException)
    assertEquals("The signed-in user changed", result.exceptionOrNull()?.message)
    verifyNoInteractions(root)
    assertArrayEquals(original, source.readBytes())
  }

  @Test
  fun downloadAtExactlyFiveMiBIsAccepted() = runTest {
    val bytes = png.copyOf(PersonalPhotoRepository.MAX_BYTES)
    serve(bytes)
    assertArrayEquals(bytes, repository.load(reference).getOrThrow())
    verify(remote).getStream(any(StreamDownloadTask.StreamProcessor::class.java))
    verify(remote, never()).downloadUrl
  }

  @Test
  fun uploadResultIsRejectedAfterSessionChangesAndSourceIsKept() = runTest {
    val source = photo(png)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          `when`(auth.currentUser).thenReturn(null)
          upload
        }
    assertTrue(
        repository.upload("home", "alice", source).exceptionOrNull() is IllegalStateException)
    assertArrayEquals(png, source.readBytes())
    verify(remote, never())
        .delete() // Cancellation/session change cannot prove the object is unused.
  }

  @Test
  fun downloadResultIsDiscardedAfterUserSwitch() = runTest {
    val bob = mock(FirebaseUser::class.java)
    `when`(bob.uid).thenReturn("bob")
    serve(png) { `when`(auth.currentUser).thenReturn(bob) }
    assertTrue(repository.load(reference).exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun logoutDuringDownloadStopsReadingAndClosesStream() = runTest {
    doAnswer {
          `when`(auth.currentUser).thenReturn(null)
          val stream = spy(png.inputStream())
          try {
            it.getArgument<StreamDownloadTask.StreamProcessor>(0)
                .doInBackground(download.result, stream)
          } finally {
            verify(stream).close()
          }
          download
        }
        .`when`(remote)
        .getStream(any(StreamDownloadTask.StreamProcessor::class.java))
    assertTrue(repository.load(reference).isFailure)
  }

  @Test
  fun cancellingTransfersCancelsSdkTasksWithoutDeletingSource() = runTest {
    val source = photo(png)
    val uploadStarted = CompletableDeferred<Unit>()
    `when`(upload.isComplete).thenReturn(false)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          uploadStarted.complete(Unit)
          upload
        }
    val pendingUpload = async { repository.upload("home", "alice", source) }
    uploadStarted.await()
    pendingUpload.cancelAndJoin()
    verify(upload).cancel()
    assertTrue(pendingUpload.isCancelled)
    assertArrayEquals(png, source.readBytes())
    val downloadStarted = CompletableDeferred<Unit>()
    `when`(download.isComplete).thenReturn(false)
    doAnswer {
          downloadStarted.complete(Unit)
          download
        }
        .`when`(remote)
        .getStream(any(StreamDownloadTask.StreamProcessor::class.java))
    val pendingDownload = async { repository.load(reference) }
    downloadStarted.await()
    pendingDownload.cancelAndJoin()
    assertTrue(pendingDownload.isCancelled)
    verify(download).cancel()
    verify(remote, never()).delete()
  }

  @Test
  fun existsReadsMetadataAndReportsWhetherThePhotoWasUploaded() = runTest {
    `when`(remote.metadata).thenReturn(Tasks.forResult(mock(StorageMetadata::class.java)))
    assertTrue(repository.exists(reference).getOrThrow())
    verify(root).child(reference.path)
    val missing = mock(StorageException::class.java)
    `when`(missing.errorCode).thenReturn(StorageException.ERROR_OBJECT_NOT_FOUND)
    `when`(remote.metadata).thenReturn(Tasks.forException(missing))
    assertFalse(repository.exists(reference).getOrThrow())
    verify(remote, never()).getStream(any(StreamDownloadTask.StreamProcessor::class.java))
  }

  @Test
  fun existsReturnsOtherErrorsAndMissingSessionAsFailures() = runTest {
    val denied = mock(StorageException::class.java)
    `when`(denied.errorCode).thenReturn(StorageException.ERROR_NOT_AUTHORIZED)
    `when`(remote.metadata).thenReturn(Tasks.forException(denied))
    assertSame(denied, repository.exists(reference).exceptionOrNull())
    `when`(auth.currentUser).thenReturn(null)
    assertEquals("No signed-in user", repository.exists(reference).exceptionOrNull()?.message)
  }
}
