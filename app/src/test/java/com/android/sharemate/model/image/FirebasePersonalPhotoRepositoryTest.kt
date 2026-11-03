// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StreamDownloadTask
import com.google.firebase.storage.UploadTask
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class FirebasePersonalPhotoRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val storage = mock(FirebaseStorage::class.java)
  private val root = mock(StorageReference::class.java)
  private val remote = mock(StorageReference::class.java)
  private val upload = mock(UploadTask::class.java)
  private val download = mock(StreamDownloadTask::class.java)
  private val repository = FirebasePersonalPhotoRepository(storage)
  private val reference =
      PersonalPhotoReference("itemPhotos/item/00000000-0000-0000-0000-000000000000")
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42)

  @Before
  fun setUp() {
    `when`(storage.reference).thenReturn(root)
    `when`(root.child(anyString())).thenReturn(remote)
    `when`(upload.isComplete).thenReturn(true)
    `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
    `when`(download.isComplete).thenReturn(true)
    `when`(download.result).thenReturn(mock(StreamDownloadTask.TaskSnapshot::class.java))
  }

  @Test
  fun uploadAndLoadPreserveContentAndOriginalAndGenerateFreshPaths() = runTest {
    var stored = byteArrayOf()
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          stored = it.getArgument(0)
          assertEquals("image/png", it.getArgument<StorageMetadata>(1).contentType)
          upload
        }
    `when`(remote.getStream(any(StreamDownloadTask.StreamProcessor::class.java))).thenAnswer {
      val stream = spy(stored.inputStream())
      it.getArgument<StreamDownloadTask.StreamProcessor>(0).doInBackground(download.result, stream)
      verify(stream).close()
      download
    }
    val source = temporary.newFile().apply { writeBytes(png) }
    val first = repository.upload("item", source).getOrThrow()
    val second = repository.upload("item", source).getOrThrow()
    assertNotEquals(first, second)
    assertArrayEquals(png, repository.load(first).getOrThrow())
    assertArrayEquals(png, source.readBytes())
    verify(remote, never()).downloadUrl
  }

  @Test
  fun invalidInputsDoNotStartTransfers() = runTest {
    val source = temporary.newFile()
    for (bytes in
        listOf(
            byteArrayOf(),
            "not a photo".toByteArray(),
            ByteArray(PersonalPhotoRepository.MAX_BYTES + 1))) {
      source.writeBytes(bytes)
      assertTrue(repository.upload("item", source).exceptionOrNull() is IllegalArgumentException)
      assertTrue(source.exists())
    }
    source.writeBytes(png)
    for (id in listOf("", " ", "a/b", ".", "..")) {
      assertTrue(repository.upload(id, source).isFailure)
    }
    assertTrue(repository.upload("item", temporary.root.resolve("missing")).isFailure)
    verifyNoInteractions(remote)
    for (path in
        listOf(
            "content://gallery/1",
            "https://example.org/photo",
            "itemPhotos/../00000000-0000-0000-0000-000000000000")) {
      assertThrows(IllegalArgumentException::class.java) { PersonalPhotoReference(path) }
    }
  }

  @Test
  fun jpegAndExactSizeLimitAreAccepted() = runTest {
    val bytes =
        ByteArray(PersonalPhotoRepository.MAX_BYTES).apply {
          this[0] = 0xff.toByte()
          this[1] = 0xd8.toByte()
          this[2] = 0xff.toByte()
        }
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          assertEquals("image/jpeg", it.getArgument<StorageMetadata>(1).contentType)
          upload
        }
    assertTrue(repository.upload("item", temporary.newFile().apply { writeBytes(bytes) }).isSuccess)
  }

  @Test
  fun transferFailuresAndDeleteConfirmationAreNotReportedAsSuccess() = runTest {
    val failure = IOException("transfer failed")
    `when`(upload.exception).thenReturn(failure)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenReturn(upload)
    assertSame(
        failure,
        repository.upload("item", temporary.newFile().apply { writeBytes(png) }).exceptionOrNull())
    `when`(remote.getStream(any(StreamDownloadTask.StreamProcessor::class.java)))
        .thenReturn(download)
    `when`(download.exception).thenReturn(failure)
    assertSame(failure, repository.load(reference).exceptionOrNull())
    `when`(remote.delete()).thenReturn(Tasks.forException(failure))
    assertSame(failure, repository.delete(reference).exceptionOrNull())
    `when`(remote.delete()).thenReturn(Tasks.forResult(null))
    assertTrue(repository.delete(reference).isSuccess)
  }

  @Test
  fun oversizedAndInvalidDownloadsAreFailures() = runTest {
    for (bytes in listOf(byteArrayOf(), ByteArray(PersonalPhotoRepository.MAX_BYTES + 1))) {
      `when`(remote.getStream(any(StreamDownloadTask.StreamProcessor::class.java))).thenAnswer {
        val stream = spy(bytes.inputStream())
        try {
          it.getArgument<StreamDownloadTask.StreamProcessor>(0)
              .doInBackground(download.result, stream)
        } finally {
          verify(stream).close()
        }
        download
      }
      assertTrue(repository.load(reference).isFailure)
    }
  }

  @Test
  fun cancellationCancelsUploadAndDownloadButNeverDeletesOriginal() = runTest {
    val source = temporary.newFile().apply { writeBytes(png) }
    val startedUpload = CompletableDeferred<Unit>()
    `when`(upload.isComplete).thenReturn(false)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          startedUpload.complete(Unit)
          upload
        }
    val pendingUpload = async { repository.upload("item", source) }
    startedUpload.await()
    pendingUpload.cancelAndJoin()
    verify(upload).cancel()
    assertTrue(pendingUpload.isCancelled)
    assertArrayEquals(png, source.readBytes())

    val startedDownload = CompletableDeferred<Unit>()
    `when`(download.isComplete).thenReturn(false)
    `when`(remote.getStream(any(StreamDownloadTask.StreamProcessor::class.java))).thenAnswer {
      startedDownload.complete(Unit)
      download
    }
    val pendingDownload = async { repository.load(reference) }
    startedDownload.await()
    pendingDownload.cancelAndJoin()
    verify(download).cancel()
    assertTrue(pendingDownload.isCancelled)
  }

  @Test
  fun deletionCancellationPropagatesWhileSdkRequestMayStillComplete() = runTest {
    val completion = TaskCompletionSource<Void>()
    `when`(remote.delete()).thenReturn(completion.task)
    val pending = async { repository.delete(reference) }
    testScheduler.runCurrent()
    pending.cancelAndJoin()
    completion.setResult(null)
    assertTrue(pending.isCancelled)
    assertTrue(completion.task.isSuccessful)
  }
}
