// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Real SDK and `storage.rules` tests for profile photos, against the Auth and Storage emulators:
 * only the signed-in owner can create a valid photo under `users/{uid}/avatars/`, read, list or
 * delete it, nobody can overwrite one, and the repository saves the download URL as the Auth photo
 * URL before deleting the previous account photo, and no other photo.
 */
@RunWith(AndroidJUnit4::class)
class ProfilePhotoStorageEmulatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val auth = FirebaseEmulator.auth
  private val storage =
      FirebaseStorage.getInstance().apply {
        useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.STORAGE_PORT)
      }
  private val repository = FirebaseProfilePhotoRepository(storage, auth)
  private val png =
      android.util.Base64.decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNYxfD/PwAFqgKpvAzkRAAAAABJRU5ErkJggg==",
          android.util.Base64.DEFAULT)
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2)
  private lateinit var owner: String

  @Before
  fun setUp() = runBlocking {
    FirebaseEmulator.clear()
    owner = FirebaseEmulator.signInAs("avatar-owner")
  }

  @After
  fun tearDown() {
    try {
      // Emulator-only admin token: delete objects the rules do not let a user delete.
      val items = JSONObject(emulatorRequest("GET", "")).optJSONArray("items")
      if (items != null)
          for (i in 0 until items.length()) {
            val name = URLEncoder.encode(items.getJSONObject(i).getString("name"), "UTF-8")
            emulatorRequest("DELETE", "/$name")
          }
    } finally {
      FirebaseEmulator.clear()
    }
  }

  private fun emulatorRequest(method: String, suffix: String): String {
    val url =
        "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.STORAGE_PORT}/v0/b/${storage.reference.bucket}/o$suffix"
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
      connection.requestMethod = method
      connection.setRequestProperty("Authorization", "Bearer owner")
      check(connection.responseCode in 200..299) {
        "Emulator cleanup: HTTP ${connection.responseCode}"
      }
      connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  @Test
  fun ownerUploadStoresPhotoAndSetsAccountPhotoUrl() = runBlocking {
    val url = upload(png)

    assertTrue(url.isNotBlank())
    val stored = photosOf(owner).single()
    assertEquals("image/png", stored.metadata.await().contentType)
    assertArrayEquals(png, stored.getBytes(MAX_READ).await())
    assertEquals(url, reloadedPhotoUrl())
  }

  @Test
  fun secondUploadBecomesTheAccountPhotoAndDeletesThePreviousOne() = runBlocking {
    val first = upload(png)
    val second = upload(jpeg)

    assertNotEquals(first, second)
    assertEquals(second, reloadedPhotoUrl())
    // Only the new photo is left, so the previous one was deleted after the account update.
    val stored = photosOf(owner).single()
    assertArrayEquals(jpeg, stored.getBytes(MAX_READ).await())
    assertEquals("image/jpeg", stored.metadata.await().contentType)
  }

  @Test
  fun uploadKeepsAnotherUploadsPhotoThatTheAccountDoesNotPointAt() = runBlocking {
    val first = upload(png)
    // Another device's upload has stored its photo but not saved it to the account yet.
    photoOf(owner, "other-device").putBytes(png, metadata("image/png")).await()

    val second = upload(jpeg)

    assertEquals(second, reloadedPhotoUrl())
    val left = photosOf(owner)
    assertEquals(2, left.size)
    assertTrue(left.any { it.name == "other-device" })
    assertTrue(left.none { it.downloadUrl.await().toString() == first })
  }

  @Test
  fun ownerCanWriteListAndDeleteTheirOwnPhotos() = runBlocking {
    val photo = photoOf(owner, "direct")
    photo.putBytes(png, metadata("image/png")).await()
    assertEquals(listOf("direct"), photosOf(owner).map { it.name })

    photo.delete().await()
    assertTrue(photosOf(owner).isEmpty())
  }

  @Test
  fun ownerCannotOverwriteAPhotoOrWriteOtherPathsUnderUsers() = runBlocking {
    upload(png)
    val stored = photosOf(owner).single()

    denied(runCatching { stored.putBytes(jpeg, metadata("image/jpeg")).await() })
    for (path in listOf("users/$owner/other.jpg", "users/$owner/avatar.jpg")) {
      denied(
          runCatching {
            storage.reference.child(path).putBytes(png, metadata("image/png")).await()
          })
    }
    assertArrayEquals(png, stored.getBytes(MAX_READ).await())
  }

  @Test
  fun anotherUserCannotCreateReplaceOrDeleteOwnersPhotos() = runBlocking {
    FirebaseEmulator.signInAs("avatar-intruder")
    denied(runCatching { photoOf(owner, "forged").putBytes(png, metadata("image/png")).await() })

    FirebaseEmulator.signInAs("avatar-owner")
    upload(png)
    val name = photosOf(owner).single().name
    FirebaseEmulator.signInAs("avatar-intruder")
    denied(runCatching { photoOf(owner, name).putBytes(jpeg, metadata("image/jpeg")).await() })
    denied(runCatching { photoOf(owner, name).delete().await() })

    FirebaseEmulator.signInAs("avatar-owner")
    assertArrayEquals(png, photoOf(owner, name).getBytes(MAX_READ).await())
  }

  @Test
  fun anotherUserCannotReadOrListOwnersPhotos() = runBlocking {
    upload(png)
    val name = photosOf(owner).single().name
    FirebaseEmulator.signInAs("avatar-reader")
    denied(runCatching { photoOf(owner, name).downloadUrl.await() })
    denied(runCatching { photoOf(owner, name).getBytes(MAX_READ).await() })
    denied(runCatching { photosOf(owner) })
  }

  @Test
  fun signedOutUserCannotWriteAPhoto() = runBlocking {
    FirebaseEmulator.signOut()
    denied(runCatching { photoOf(owner, "anonymous").putBytes(png, metadata("image/png")).await() })
  }

  @Test
  fun nonImageContentTypeIsRejected() = runBlocking {
    denied(runCatching { photoOf(owner, "text").putBytes(png, metadata("text/plain")).await() })
  }

  @Test
  fun emptyOrOversizedPhotoIsRejected() = runBlocking {
    for (bytes in listOf(byteArrayOf(), png.copyOf(PersonalPhotoRepository.MAX_BYTES + 1))) {
      denied(runCatching { photoOf(owner, "size").putBytes(bytes, metadata("image/png")).await() })
    }
  }

  @Test
  fun repositoryRejectsAnExpectedUidThatIsNotTheSignedInUser() = runBlocking {
    val other = FirebaseEmulator.signInAs("avatar-other")
    val result = repository.upload(owner, temporary.newFile().apply { writeBytes(png) })

    assertTrue("$result", result.exceptionOrNull() is IllegalStateException)
    assertNotEquals(owner, other)
    assertTrue(photosOf(other).isEmpty())
    assertNull(reloadedPhotoUrl())
  }

  private suspend fun upload(bytes: ByteArray): String {
    val source = temporary.newFile().apply { writeBytes(bytes) }
    return repository.upload(owner, source).getOrThrow().also {
      assertArrayEquals(bytes, source.readBytes())
    }
  }

  private fun photoOf(uid: String, photoId: String) =
      storage.reference.child(ProfilePhotoPaths.avatar(uid, photoId))

  private suspend fun photosOf(uid: String) =
      storage.reference.child(ProfilePhotoPaths.folder(uid)).listAll().await().items

  private suspend fun reloadedPhotoUrl(): String? {
    val user = checkNotNull(auth.currentUser)
    user.reload().await()
    return checkNotNull(auth.currentUser).photoUrl?.toString()
  }

  private fun metadata(contentType: String): StorageMetadata =
      StorageMetadata.Builder().setContentType(contentType).build()

  /** Asserts [result] failed with the Storage permission error [code]. */
  private fun denied(result: Result<*>, code: Int = StorageException.ERROR_NOT_AUTHORIZED) {
    val error = result.exceptionOrNull()
    assertTrue(
        "Expected Storage error $code, got $result",
        error is StorageException && error.errorCode == code)
  }

  private companion object {
    const val MAX_READ = PersonalPhotoRepository.MAX_BYTES.toLong() + 1
  }
}
