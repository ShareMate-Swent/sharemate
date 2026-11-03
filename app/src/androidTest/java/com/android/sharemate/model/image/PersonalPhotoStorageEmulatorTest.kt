// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Real SDK/rules tests. Every service is explicitly routed to a local emulator. */
@RunWith(AndroidJUnit4::class)
class PersonalPhotoStorageEmulatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val firestore = FirebaseEmulator.firestore
  private val storage =
      FirebaseStorage.getInstance().apply { useEmulator(FirebaseEmulator.HOST, 9199) }
  private val repository = FirebasePersonalPhotoRepository(storage)
  private val png =
      android.util.Base64.decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1cAAAAASUVORK5CYII=",
          android.util.Base64.DEFAULT)
  private lateinit var owner: String
  private lateinit var itemId: String

  @Before
  fun setUp() = runBlocking {
    firestore.enableNetwork().await()
    FirebaseEmulator.clear()
    owner = FirebaseEmulator.signInAs("photo-owner")
    itemId = UUID.randomUUID().toString()
  }

  @After
  fun tearDown() {
    try {
      // Emulator-only admin token: clean up even when the associated item has been deleted.
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
    val url = "http://${FirebaseEmulator.HOST}:9199/v0/b/${storage.reference.bucket}/o$suffix"
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
  fun anotherMemberReadsPersonalItemPhotoAndCanUploadAndDelete() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Photo home", owner)
    createItem(home.id)
    val original = upload()
    assertArrayEquals(png, repository.load(original).getOrThrow())
    val member = FirebaseEmulator.signInAs("photo-member")
    homes.joinHousehold(home.inviteCode, member)
    // A new repository and reconstructed path model a second device, without a download token.
    val anotherDevice = FirebasePersonalPhotoRepository(storage)
    assertArrayEquals(png, anotherDevice.load(PersonalPhotoReference(original.path)).getOrThrow())
    val second = upload()
    assertNotEquals(original, second)
    anotherDevice.delete(original).getOrThrow()
    assertEquals(
        StorageException.ERROR_OBJECT_NOT_FOUND,
        (repository.load(original).exceptionOrNull() as StorageException).errorCode)
    homes.leaveHousehold(member)
    denied(repository.load(second))
    FirebaseEmulator.signInAs("photo-owner")
    homes.leaveHousehold(owner)
    assertArrayEquals(png, repository.load(second).getOrThrow())
    repository.delete(second).getOrThrow()
  }

  @Test
  fun outsiderAndSignedOutUserCannotReadCreateOrDeleteEvenWithForgedProfile() = runBlocking {
    val home = HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner)
    createItem(home.id)
    val reference = upload()
    val outsider = FirebaseEmulator.signInAs("photo-outsider")
    firestore.collection("users").document(outsider).set(mapOf("householdId" to home.id)).await()
    for (signedIn in listOf(true, false)) {
      if (!signedIn) FirebaseEmulator.signOut()
      denied(repository.load(reference))
      denied(repository.upload(itemId, temporary.newFile().apply { writeBytes(png) }))
      denied(repository.delete(reference))
    }
    FirebaseEmulator.signInAs("photo-owner")
    assertArrayEquals(png, repository.load(reference).getOrThrow())
  }

  @Test
  fun standaloneAndPrivateFridgePhotosFollowItemOwnership() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val privateHome = homes.createHousehold("Private", owner, FridgeType.PRIVATE)
    for (home in listOf(null, privateHome.id)) {
      FirebaseEmulator.signInAs("photo-owner")
      createItem(home)
      val reference = upload()
      val outsider = FirebaseEmulator.signInAs("photo-outsider")
      if (home != null) {
        FirebaseEmulator.seedLegacyDocument(
            "households",
            home,
            """{"memberIds":{"arrayValue":{"values":[{"stringValue":"$owner"},{"stringValue":"$outsider"}]}}}""")
      }
      denied(repository.load(reference))
      denied(repository.delete(reference))
      FirebaseEmulator.signInAs("photo-owner")
      repository.delete(reference).getOrThrow()
    }
  }

  @Test
  fun rulesRejectInvalidMetadataSizesOverwritesListingAndMissingItems() = runBlocking {
    createItem(null)
    val reference = upload()
    val validMetadata = StorageMetadata.Builder().setContentType("image/png").build()
    val objectRef = storage.reference.child(reference.path)
    suspend fun rejected(bytes: ByteArray, metadata: StorageMetadata) {
      val fresh = storage.reference.child("itemPhotos/$itemId/${UUID.randomUUID()}")
      denied(runCatching { fresh.putBytes(bytes, metadata).await() })
    }
    rejected(png, StorageMetadata.Builder().setContentType("text/plain").build())
    rejected(byteArrayOf(), validMetadata)
    rejected(ByteArray(PersonalPhotoRepository.MAX_BYTES + 1), validMetadata)
    denied(runCatching { objectRef.putBytes(png, validMetadata).await() })
    denied(runCatching { objectRef.updateMetadata(validMetadata).await() })
    denied(runCatching { storage.reference.child("itemPhotos/$itemId").listAll().await() })
    denied(repository.upload("missing-item", temporary.newFile().apply { writeBytes(png) }))
    firestore.collection("items").document(itemId).delete().await()
    denied(repository.load(reference))
    denied(repository.delete(reference))
  }

  @Test
  fun cancellationActuallyCancelsSdkUpload() = runBlocking {
    createItem(null)
    val reading = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val source =
        object : ByteArrayInputStream(png.copyOf(PersonalPhotoRepository.MAX_BYTES)) {
          override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reading.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            return super.read(buffer, offset, length)
          }
        }
    source.use {
      val reference = storage.reference.child("itemPhotos/$itemId/${UUID.randomUUID()}")
      val task =
          reference.putStream(it, StorageMetadata.Builder().setContentType("image/png").build())
      val waiting = async(start = CoroutineStart.UNDISPATCHED) { task.awaitPhotoTransfer() }
      try {
        withTimeout(10_000) { reading.await() }
        waiting.cancelAndJoin()
      } finally {
        release.countDown()
      }
      withTimeout(10_000) { while (!task.isCanceled) delay(10) }
      assertTrue(waiting.isCancelled)
      assertTrue(task.isCanceled)
      val missing = runCatching { reference.metadata.await() }.exceptionOrNull() as StorageException
      assertEquals(StorageException.ERROR_OBJECT_NOT_FOUND, missing.errorCode)
    }
  }

  @Test
  fun cancellationStopsAnOpenedSdkDownloadAndClosesItsStream() = runBlocking {
    createItem(null)
    val reference = upload()
    val opened = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val task =
        storage.reference.child(reference.path).getStream { _, stream ->
          try {
            stream.use {
              opened.complete(Unit)
              check(release.await(10, TimeUnit.SECONDS))
              it.read()
            }
          } finally {
            closed.complete(Unit)
          }
        }
    val waiting = async(start = CoroutineStart.UNDISPATCHED) { task.awaitPhotoTransfer() }
    try {
      withTimeout(10_000) { opened.await() }
      waiting.cancelAndJoin()
    } finally {
      release.countDown()
    }
    withTimeout(10_000) {
      closed.await()
      while (!task.isCanceled) delay(10)
    }
    assertTrue(waiting.isCancelled)
    assertTrue(task.isCanceled)
  }

  private suspend fun createItem(householdId: String?) {
    firestore
        .collection("items")
        .document(itemId)
        .set(
            mapOf(
                "ownerId" to owner,
                "householdId" to householdId,
                "sharing" to "PERSONAL",
                "name" to "Photo item"))
        .await()
  }

  private suspend fun upload(): PersonalPhotoReference {
    val source = temporary.newFile().apply { writeBytes(png) }
    return repository.upload(itemId, source).getOrThrow().also {
      assertArrayEquals(png, source.readBytes())
    }
  }

  private fun denied(result: Result<*>) {
    val error = result.exceptionOrNull()
    assertTrue("Expected a Storage permission failure, got $result", error is StorageException)
    assertTrue(
        (error as StorageException).errorCode in
            listOf(StorageException.ERROR_NOT_AUTHORIZED, StorageException.ERROR_NOT_AUTHENTICATED))
  }
}
