// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.incident

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Real SDK/rules tests, including writes that bypass the repository's local validation. */
@RunWith(AndroidJUnit4::class)
class IncidentPhotoStorageEmulatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val app =
      FirebaseApp.getInstance().also {
        check(it.options.projectId?.startsWith("demo-") == true) {
          "Demo Firebase project required"
        }
      }
  private val firestore = FirebaseEmulator.firestore
  private val auth = FirebaseEmulator.auth
  private val storage =
      FirebaseStorage.getInstance(app).apply {
        useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.STORAGE_PORT)
      }
  private val homes = HouseholdRepositoryFirestore(firestore)
  private val repository = FirebaseIncidentPhotoRepository(storage, auth)
  private val png =
      android.util.Base64.decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNYxfD/PwAFqgKpvAzkRAAAAABJRU5ErkJggg==",
          android.util.Base64.DEFAULT)
  private lateinit var owner: String
  private lateinit var member: String
  private lateinit var home: String

  @Before
  fun setUp(): Unit = runBlocking {
    FirebaseEmulator.clear()
    owner = FirebaseEmulator.signInAs("incident-owner")
    val household = homes.createHousehold("Incident tests", owner)
    home = household.id
    member = FirebaseEmulator.signInAs("incident-member")
    homes.joinHousehold(household.inviteCode, member)
    FirebaseEmulator.signInAs("incident-owner")
  }

  @After
  fun tearDown() {
    FirebaseEmulator.clear()
  }

  private suspend fun upload(): IncidentPhotoReference {
    val source = temporary.newFile().apply { writeBytes(png) }
    val reference = repository.upload(home, checkNotNull(auth.currentUser).uid, source).getOrThrow()
    assertArrayEquals(png, source.readBytes())
    return reference
  }

  private fun path(author: String = owner) =
      "${IncidentPhotoReference.ROOT}/$home/$author/${UUID.randomUUID()}"

  private suspend fun rawUpload(path: String, bytes: ByteArray = png, mime: String = "image/png") {
    storage.reference
        .child(path)
        .putBytes(bytes, StorageMetadata.Builder().setContentType(mime).build())
        .await()
  }

  private suspend fun denied(action: suspend () -> Unit) {
    val error = runCatching { withTimeout(20_000) { action() } }.exceptionOrNull()
    assertTrue("Expected Storage authorization refusal, got $error", error is StorageException)
    assertEquals(StorageException.ERROR_NOT_AUTHORIZED, (error as StorageException).errorCode)
  }

  @Test
  fun ownerUploadsAndAnotherMemberDownloadsUsingOnlyTheStoredReference() = runBlocking {
    val reference = upload()
    assertEquals(home, reference.householdId)
    assertEquals(owner, reference.authorId)
    assertArrayEquals(png, repository.load(reference).getOrThrow())
    FirebaseEmulator.signInAs("incident-member")
    val secondDevice = FirebaseIncidentPhotoRepository(storage, auth)
    assertArrayEquals(png, secondDevice.load(IncidentPhotoReference(reference.path)).getOrThrow())
  }

  @Test
  fun forgedAuthorIsDeniedByStorageEvenForACurrentMember() = runBlocking {
    denied { rawUpload(path(member)) }
  }

  @Test
  fun outsidersAndUnauthenticatedUsersCannotReadOrCreate() = runBlocking {
    val reference = upload()
    val outsider = FirebaseEmulator.signInAs("incident-outsider")
    denied {
      storage.reference
          .child(reference.path)
          .getBytes(PersonalPhotoRepository.MAX_BYTES.toLong())
          .await()
    }
    denied { rawUpload(path(outsider)) }
    FirebaseEmulator.signOut()
    denied { rawUpload(path()) }
    denied {
      storage.reference
          .child(reference.path)
          .getBytes(PersonalPhotoRepository.MAX_BYTES.toLong())
          .await()
    }
  }

  @Test
  fun leavingRevokesAccessEvenToTheMembersOwnIncidentPhotos() = runBlocking {
    FirebaseEmulator.signInAs("incident-member")
    val reference = upload()
    homes.leaveHousehold(member)
    denied {
      storage.reference
          .child(reference.path)
          .getBytes(PersonalPhotoRepository.MAX_BYTES.toLong())
          .await()
    }
    denied { rawUpload(path(member)) }
  }

  @Test
  fun rulesEnforceSizeAndMimeEvenWhenClientValidationIsBypassed() = runBlocking {
    for (mime in listOf("image/gif", "text/plain", "application/octet-stream")) denied {
      rawUpload(path(), mime = mime)
    }
    denied { rawUpload(path(), byteArrayOf()) }
    denied { rawUpload(path(), png.copyOf(PersonalPhotoRepository.MAX_BYTES + 1)) }
    rawUpload(path(), png.copyOf(PersonalPhotoRepository.MAX_BYTES))
    rawUpload(path(), byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 42), "image/jpeg")
  }

  @Test
  fun authorCannotOverwriteAnExistingPhoto() = runBlocking {
    val reference = upload()
    denied { rawUpload(reference.path) }
    denied {
      rawUpload(
          reference.path, byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 42), "image/jpeg")
    }
    assertArrayEquals(png, repository.load(reference).getOrThrow())
  }

  @Test
  fun authorCannotUpdatePhotoMetadata() = runBlocking {
    val remote = storage.reference.child(upload().path)
    denied {
      remote.updateMetadata(StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
    }
  }

  @Test
  fun authorCannotListIncidentPhotos() = runBlocking {
    upload()
    denied { storage.reference.child("${IncidentPhotoReference.ROOT}/$home").listAll().await() }
  }

  @Test
  fun authorCannotDeleteAnIncidentPhoto() = runBlocking {
    val reference = upload()
    denied { storage.reference.child(reference.path).delete().await() }
    assertArrayEquals(png, repository.load(reference).getOrThrow())
  }

  @Test
  fun malformedPathsCannotBypassRules() = runBlocking {
    for (invalid in
        listOf(
            "${IncidentPhotoReference.ROOT}/$home/$owner/not-a-uuid",
            "${path()}/extra",
            "incidents/$home/$owner/${UUID.randomUUID()}")) {
      denied { rawUpload(invalid) }
    }
  }
}
