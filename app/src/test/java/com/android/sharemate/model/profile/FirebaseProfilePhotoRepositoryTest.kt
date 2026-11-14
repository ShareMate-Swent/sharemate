// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import android.net.Uri
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.storage.FirebaseStorage
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
 * Unit tests for [FirebaseProfilePhotoRepository] with mocked Firebase Storage and Auth: the
 * storage path and content type, the Auth photo URL update, validation before any transfer, the
 * signed-in user check, failure propagation between steps and cancellation of the upload.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseProfilePhotoRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val storage = mock(FirebaseStorage::class.java)
  private val auth = mock(FirebaseAuth::class.java)
  private val user = mock(FirebaseUser::class.java)
  private val root = mock(StorageReference::class.java)
  private val remote = mock(StorageReference::class.java)
  private val upload = mock(UploadTask::class.java)
  private val repository = FirebaseProfilePhotoRepository(storage, auth)
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42)
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42)

  @Before
  fun setUp() {
    `when`(storage.reference).thenReturn(root)
    `when`(root.child(anyString())).thenReturn(remote)
    `when`(upload.isComplete).thenReturn(true)
    `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
    `when`(remote.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
        .thenReturn(upload)
    `when`(remote.downloadUrl).thenReturn(Tasks.forResult(Uri.parse(URL)))
    `when`(auth.currentUser).thenReturn(user)
    `when`(user.uid).thenReturn(UID)
    `when`(user.updateProfile(any(UserProfileChangeRequest::class.java)))
        .thenReturn(Tasks.forResult<Void>(null))
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

    verify(root).child("users/$UID/avatar.jpg")
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
  fun pngAndExactSizeLimitAreAccepted() = runTest {
    val metadata = ArgumentCaptor.forClass(StorageMetadata::class.java)
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())
    val atLimit = png.copyOf(PersonalPhotoRepository.MAX_BYTES)
    assertEquals(URL, repository.upload(UID, photo(atLimit)).getOrThrow())

    verify(remote, times(2)).putBytes(any(ByteArray::class.java), metadata.capture())
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
  }

  @Test
  fun downloadUrlFailureSkipsProfileUpdate() = runTest {
    val failure = IOException("download URL failed")
    `when`(remote.downloadUrl).thenReturn(Tasks.forException(failure))

    assertSame(failure, repository.upload(UID, photo(png)).exceptionOrNull())

    verify(user, never()).updateProfile(any(UserProfileChangeRequest::class.java))
  }

  @Test
  fun profileUpdateFailureIsReturnedAndRetryOverwritesTheSamePath() = runTest {
    val failure = IOException("profile update failed")
    `when`(user.updateProfile(any(UserProfileChangeRequest::class.java)))
        .thenReturn(Tasks.forException(failure))
        .thenReturn(Tasks.forResult<Void>(null))
    val source = photo(png)

    assertSame(failure, repository.upload(UID, source).exceptionOrNull())
    assertEquals(URL, repository.upload(UID, source).getOrThrow())

    verify(root, times(2)).child("users/$UID/avatar.jpg")
    verify(remote, times(2)).putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java))
    verify(user, times(2)).updateProfile(any(UserProfileChangeRequest::class.java))
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
  }

  @Test
  fun avatarPathIsBuiltFromConstantsAndRejectsInvalidUids() {
    assertEquals("users/$UID/avatar.jpg", ProfilePhotoPaths.avatar(UID))
    for (uid in listOf("", " ", "a/b", ".", "..")) {
      assertThrows(IllegalArgumentException::class.java) { ProfilePhotoPaths.avatar(uid) }
    }
  }

  private companion object {
    const val UID = "uid"
    const val URL = "https://storage.example/users/uid/avatar.jpg?alt=media&token=t"
  }
}
