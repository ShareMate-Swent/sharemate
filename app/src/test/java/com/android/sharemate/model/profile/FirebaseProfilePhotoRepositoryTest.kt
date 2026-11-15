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
 * Unit tests for [FirebaseProfilePhotoRepository] with mocked Firebase Storage and Auth: the new
 * storage path and content type, the Auth photo URL update, deletion of only the previous account
 * photo and only after that update, validation before any transfer, the signed-in user check,
 * failure propagation between steps and cancellation of the upload.
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
  private var nextId = 0
  private val repository = FirebaseProfilePhotoRepository(storage, auth) { "photo-${++nextId}" }
  private val remote = reference("$FOLDER/photo-1")
  private val previous = reference("$FOLDER/previous")
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42)
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42)

  @Before
  fun setUp() {
    `when`(storage.reference).thenReturn(root)
    `when`(root.child(anyString())).thenAnswer { reference(it.getArgument(0)) }
    `when`(upload.isComplete).thenReturn(true)
    `when`(upload.result).thenReturn(mock(UploadTask.TaskSnapshot::class.java))
    `when`(root.bucket).thenReturn(BUCKET)
    `when`(auth.currentUser).thenReturn(user)
    `when`(user.uid).thenReturn(UID)
    `when`(user.photoUrl).thenReturn(Uri.parse(downloadUrl("$FOLDER/previous")))
    `when`(user.updateProfile(any(UserProfileChangeRequest::class.java)))
        .thenReturn(Tasks.forResult<Void>(null))
  }

  /** Returns the mock Storage reference at [path]. */
  private fun reference(path: String): StorageReference =
      references.getOrPut(path) {
        mock(StorageReference::class.java).also { reference ->
          `when`(reference.name).thenReturn(path.substringAfterLast('/'))
          `when`(reference.path).thenReturn("/$path")
          `when`(reference.putBytes(any(ByteArray::class.java), any(StorageMetadata::class.java)))
              .thenReturn(upload)
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
  fun previousAccountPhotoIsDeletedOnlyAfterTheAccountPointsAtTheNewOne() = runTest {
    repository.upload(UID, photo(png)).getOrThrow()

    val order = inOrder(user, previous)
    order.verify(user).updateProfile(any(UserProfileChangeRequest::class.java))
    order.verify(previous).delete()
    verify(remote, never()).delete()
  }

  @Test
  fun overlappingUploadsNeverDeleteEachOthersPhoto() = runTest {
    // Two uploads (e.g. from two devices) both start while the account still points at "previous".
    // Each one may delete only that photo, never the other upload's photo, whichever saves last.
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())
    assertEquals(URL, repository.upload(UID, photo(jpeg)).getOrThrow())

    verify(previous, times(2)).delete()
    verify(remote, never()).delete()
    verify(reference("$FOLDER/photo-2"), never()).delete()
    verify(root, never()).child(FOLDER)
  }

  @Test
  fun previousPhotoOutsideTheUsersAvatarsIsNeverDeleted() = runTest {
    `when`(user.photoUrl).thenReturn(null)
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())

    // A Google account photo and another bucket's URL are not this app's photos; the others are
    // not this user's avatars.
    val foreign =
        listOf(
            "https://lh3.googleusercontent.com/a/photo",
            downloadUrl("$FOLDER/previous", bucket = "other-bucket"),
            downloadUrl("users/other/avatars/photo"),
            downloadUrl("users/$UID/other.jpg"))
    for (url in foreign) {
      `when`(user.photoUrl).thenReturn(Uri.parse(url))
      assertEquals(url, URL, repository.upload(UID, photo(png)).getOrThrow())
    }
    verify(reference("users/other/avatars/photo"), never()).delete()
    verify(reference("users/$UID/other.jpg"), never()).delete()
    verify(previous, never()).delete()
  }

  @Test
  fun failedCleanupStillReturnsTheNewPhoto() = runTest {
    `when`(previous.delete()).thenReturn(Tasks.forException(IOException("delete failed")))
    assertEquals(URL, repository.upload(UID, photo(png)).getOrThrow())
    verify(previous).delete()
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
    // The retry's success removes the previous account photo. The unused first attempt is kept:
    // it cannot be told apart from another device's upload that may now be the account photo.
    verify(previous).delete()
    verify(remote, never()).delete()
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
  fun objectPathIsReadFromDownloadUrlsOfTheBucketOnly() {
    val emulator =
        "http://10.0.2.2:9199/v0/b/$BUCKET/o/users%2F$UID%2Favatars%2Fp?alt=media&token=t"
    assertEquals("$FOLDER/p", ProfilePhotoPaths.objectPath(emulator, BUCKET))
    assertEquals("$FOLDER/a+b", ProfilePhotoPaths.objectPath(downloadUrl("$FOLDER/a+b"), BUCKET))
    for (url in
        listOf(
            downloadUrl("$FOLDER/p", bucket = "other-bucket"),
            "https://lh3.googleusercontent.com/a/photo",
            "https://host/v1/b/$BUCKET/o/p",
            "https://host/v0/b/$BUCKET/o/",
            "https://host/prefix/v0/b/$BUCKET/o/p",
            "not a url %")) {
      assertNull(url, ProfilePhotoPaths.objectPath(url, BUCKET))
    }
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
    verify(previous, never()).delete()
  }

  private companion object {
    const val UID = "uid"
    const val FOLDER = "users/$UID/avatars"
    const val BUCKET = "bucket"

    /** Returns a Firebase Storage download URL of [path] in [bucket]. */
    fun downloadUrl(path: String, bucket: String = BUCKET): String =
        "https://firebasestorage.googleapis.com/v0/b/$bucket/o/" +
            java.net.URLEncoder.encode(path, "UTF-8") +
            "?alt=media&token=t"

    const val URL = "https://storage.example/users/uid/avatars/photo-1?alt=media&token=t"
  }
}
