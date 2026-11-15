// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import android.net.Uri
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ListResult
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.io.File
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
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FirebaseProfilePhotoRepository] with mocked Firebase Storage and Auth: the new
 * storage path and content type, the Auth photo URL update, deletion of older photos only after
 * that update, validation before any transfer, the signed-in user check, failure propagation
 * between steps and cancellation of the upload.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseProfilePhotoRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val storage = mock(FirebaseStorage::class.java)
  private val auth = mock(FirebaseAuth::class.java)
  private val user = mock(FirebaseUser::class.java)
  private val root = mock(StorageReference::class.java)
  private val upload = mock(UploadTask::class.java)
  private val references = mutableMapOf<String, StorageReference>()
  private val stored = mutableListOf<StorageReference>()
  private var nextId = 0
  private val repository = FirebaseProfilePhotoRepository(storage, auth) { "photo-${++nextId}" }
  private val folder = reference(FOLDER)
  private val remote = reference("$FOLDER/photo-1")
  private val previous = reference("$FOLDER/previous").also { stored += it }
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42)
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42)

  @Before
  fun setUp() {
    `when`(storage.reference).thenReturn(root)
    `when`(root.child(anyString())).thenAnswer { reference(it.getArgument(0)) }
    `when`(upload.isComplete).thenReturn(true)
    `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
    `when`(folder.listAll()).thenAnswer {
      val listing = mock(ListResult::class.java)
      `when`(listing.items).thenReturn(stored.toList())
      Tasks.forResult(listing)
    }
    `when`(auth.currentUser).thenReturn(user)
    `when`(user.uid).thenReturn(UID)
    `when`(user.updateProfile(any(UserProfileChangeRequest::class.java)))
        .thenReturn(Tasks.forResult<Void>(null))
  }

  /** Returns the mock at [path]; uploading to it adds it to the folder listing. */
  private fun reference(path: String): StorageReference =
      references.getOrPut(path) {
        mock(StorageReference::class.java).also { reference ->
          `when`(reference.name).thenReturn(path.substringAfterLast('/'))
          `when`(reference.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
              .thenAnswer {
                stored += reference
                upload
              }
          `when`(reference.downloadUrl).thenReturn(Tasks.forResult(Uri.parse(URL)))
          `when`(reference.delete()).thenReturn(Tasks.forResult<Void>(null))
        }
      }

  private fun photo(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

  private fun verifyNoTransfer() {
    verify(remote, never()).putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java))
    verify(user, never()).updateProfile(any(UserProfileChangeRequest::class.java))
  }

  @Test
  fun jpegIsStoredAtAvatarPathAndBecomesTheAccountPhoto() = runTest {
    val source = photo(jpeg)

    assertEquals(URL, repository.upload(UID, source).getOrThrow())

    verify(root).child("$FOLDER/photo-1")
    val bytes = ArgumentCaptor.forClass(ByteArray::class.java)
    val metadata = ArgumentCaptor.forClass(StorageMetadata::class.java)
    verify(remote).putBytes(bytes.capture(), metadata.capture())
    assertArrayEquals(jpeg, bytes.value)
    assertEquals("image/jpeg", metadata.value.contentType)
    val request = ArgumentCaptor.forClass(UserProfileChangeRequest::class.java)
    verify(user).updateProfile(request.capture())
    assertEquals(URL, request.value.photoUri.toString())
    assertArrayEquals(jpeg, source.readBytes())
  }

  @Test
  fun olderPhotosAreDeletedOnlyAfterTheAccountPointsAtTheNewOne() = runTest {
    repository.upload(UID, photo(png)).getOrThrow()

    val order = inOrder(user, folder, previous)
    order.verify(user).updateProfile(any(UserProfileChangeRequest::class.java))
    order.verify(folder).listAll()
    order.verify(previous).delete()
    verify(remote, never()).delete()
  }

  @Test
  fun failedCleanupStillReturnsTheNewPhoto() = runTest {
    `when`(previous.delete()).thenReturn(Tasks.forException(IOException("delete failed")))
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())

    `when`(folder.listAll()).thenReturn(Tasks.forException(IOException("list failed")))
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())
  }

  @Test
  fun pngAndExactSizeLimitAreAccepted() = runTest {
    val metadata = ArgumentCaptor.forClass(StorageMetadata::class.java)
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())
    val atLimit = png.copyOf(PersonalPhotoRepository.MAX_BYTES)
    assertEquals(URL, repository.upload(UID, photo(atLimit)).getOrThrow())

    verify(remote).putBytes(any(ByteArray::class.java), metadata.capture())
    verify(reference("$FOLDER/photo-2")).putBytes(any(ByteArray::class.java), metadata.capture())
    assertEquals(listOf("image/png", "image/png"), metadata.allValues.map { it.contentType })
  }

  @Test
  fun emptyNonImageOrOversizedPhotosFailBeforeAnyTransfer() = runTest {
    for (bytes in
        listOf(
            byteArrayOf(),
            "not a photo".toByteArray(),
            png.copyOf(PersonalPhotoRepository.MAX_BYTES + 1))) {
      val result = repository.upload(UID, photo(bytes))
      assertTrue(
          "${bytes.size} bytes: $result", result.exceptionOrNull() is IllegalArgumentException)
    }
    verifyNoTransfer()
  }

  @Test
  fun missingSourceFileIsAFailureWithoutTransfer() = runTest {
    assertTrue(repository.upload(UID, temporary.root.resolve("missing")).isFailure)
    verifyNoTransfer()
  }

  @Test
  fun noSignedInUserOrAnotherUserFailsWithoutTransfer() = runTest {
    `when`(auth.currentUser).thenReturn(null)
    assertTrue(repository.upload(UID, photo(png)).exceptionOrNull() is IllegalStateException)

    `when`(auth.currentUser).thenReturn(user)
    assertTrue(
        repository.upload("another-uid", photo(png)).exceptionOrNull() is IllegalStateException)

    verifyNoInteractions(root)
    verifyNoTransfer()
  }

  @Test
  fun uploadFailureSkipsDownloadUrlAndProfileUpdate() = runTest {
    val failure = IOException("upload failed")
    `when`(upload.exception).thenReturn(failure)

    assertSame(failure, repository.upload(UID, photo(png)).exceptionOrNull())

    verify(remote, never()).downloadUrl
    verify(user, never()).updateProfile(any(UserProfileChangeRequest::class.java))
    verifyPreviousPhotoKept()
  }

  @Test
  fun downloadUrlFailureSkipsProfileUpdateAndKeepsThePreviousPhoto() = runTest {
    val failure = IOException("download URL failed")
    `when`(remote.downloadUrl).thenReturn(Tasks.forException(failure))

    assertSame(failure, repository.upload(UID, photo(png)).exceptionOrNull())

    verify(user, never()).updateProfile(any(UserProfileChangeRequest::class.java))
    verifyPreviousPhotoKept()
  }

  @Test
  fun profileUpdateFailureKeepsThePreviousPhotoAndRetryUsesANewPath() = runTest {
    val failure = IOException("profile update failed")
    `when`(user.updateProfile(any(UserProfileChangeRequest::class.java)))
        .thenReturn(Tasks.forException(failure))
        .thenReturn(Tasks.forResult<Void>(null))
    val source = photo(png)

    assertSame(failure, repository.upload(UID, source).exceptionOrNull())
    verifyPreviousPhotoKept()
    verify(remote, never()).delete()

    assertEquals(URL, repository.upload(UID, source).getOrThrow())
    verify(root).child("$FOLDER/photo-2")
    // The retry's success removes both the previous photo and the unused first attempt.
    verify(previous).delete()
    verify(remote).delete()
    verify(reference("$FOLDER/photo-2"), never()).delete()
  }

  @Test
  fun cancellationDuringUploadCancelsTheSdkTaskAndSkipsLaterSteps() = runTest {
    val started = CompletableDeferred<Unit>()
    `when`(upload.isComplete).thenReturn(false)
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenAnswer {
          started.complete(Unit)
          upload
        }

    val pending = async { repository.upload(UID, photo(png)) }
    started.await()
    pending.cancelAndJoin()

    assertTrue(pending.isCancelled)
    verify(upload).cancel()
    verify(remote, never()).downloadUrl
    verify(user, never()).updateProfile(any(UserProfileChangeRequest::class.java))
    verifyPreviousPhotoKept()
  }

  @Test
  fun avatarPathsRejectInvalidSegments() {
    assertEquals("users/$UID/avatars", ProfilePhotoPaths.folder(UID))
    assertEquals("users/$UID/avatars/p", ProfilePhotoPaths.avatar(UID, "p"))
    for (segment in listOf("", " ", "a/b", ".", "..")) {
      assertThrows(IllegalArgumentException::class.java) { ProfilePhotoPaths.folder(segment) }
      assertThrows(IllegalArgumentException::class.java) { ProfilePhotoPaths.avatar(UID, segment) }
    }
  }

  private fun verifyPreviousPhotoKept() {
    verify(folder, never()).listAll()
    verify(previous, never()).delete()
  }

  private companion object {
    const val UID = "uid"
    const val FOLDER = "users/$UID/avatars"
    const val URL = "https://storage.example/users/uid/avatars/photo-1?alt=media&token=t"
  }
}
