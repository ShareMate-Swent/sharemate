// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.image

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.fridge.decodePersonalThumbnail
import com.android.sharemate.ui.fridge.testProductCatalogRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.FirebaseFirestoreException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
      FirebaseStorage.getInstance().apply {
        useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.STORAGE_PORT)
      }
  private val repository = FirebasePersonalPhotoRepository(storage, firestore)
  private val png =
      android.util.Base64.decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNYxfD/PwAFqgKpvAzkRAAAAABJRU5ErkJggg==",
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

  /** Emulator-only partial update; omitted fields in the update mask are deleted. */
  private fun patchHousehold(home: String, field: String, fieldsJson: String) {
    val projectId = checkNotNull(firestore.app.options.projectId)
    val url =
        "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}/v1/projects/$projectId/databases/(default)/documents/households/$home?updateMask.fieldPaths=$field"
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.doOutput = true
      connection.outputStream.use { it.write("""{"fields":$fieldsJson}""".toByteArray()) }
      check(connection.responseCode in 200..299)
    } finally {
      connection.disconnect()
    }
  }

  @Test
  fun nonOwnerMemberReadsPhotoInHouseholdWithoutType() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Legacy home", owner)
    val member = FirebaseEmulator.signInAs("photo-legacy-member")
    homes.joinHousehold(home.inviteCode, member)
    assertNotEquals(owner, member)
    FirebaseEmulator.signInAs("photo-owner")
    createItem(home.id, sharing = "SHARED")
    val photo = upload()
    // Remove type after setup to isolate Storage compatibility from Firestore's schema policy.
    patchHousehold(home.id, "type", "{}")
    FirebaseEmulator.signInAs("photo-legacy-member")
    assertArrayEquals(png, repository.load(photo).getOrThrow())
  }

  @Test
  fun anotherMemberReadsSharedItemPhotoAndCanUploadAndDelete() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Photo home", owner)
    createItem(home.id, sharing = "SHARED")
    val original = upload()
    assertArrayEquals(png, repository.load(original).getOrThrow())
    val member = FirebaseEmulator.signInAs("photo-member")
    homes.joinHousehold(home.inviteCode, member)
    // A new repository and reconstructed path model a second device, without a download token.
    val anotherDevice = FirebasePersonalPhotoRepository(storage, firestore)
    assertArrayEquals(png, anotherDevice.load(PersonalPhotoReference(original.path)).getOrThrow())
    val second = upload()
    assertNotEquals(original, second)
    anotherDevice.delete(original).getOrThrow()
    assertEquals(
        StorageException.ERROR_OBJECT_NOT_FOUND,
        (repository.load(original).exceptionOrNull() as StorageException).errorCode)
    homes.leaveHousehold(member)
    denied(repository.load(second))
    // Leaving the household also cuts the owner off from their own photos, which stay in place.
    FirebaseEmulator.signInAs("photo-owner")
    homes.leaveHousehold(owner)
    denied(repository.load(second))
    denied(repository.delete(second))
    denied(repository.upload(itemId, temporary.newFile().apply { writeBytes(png) }))
    homes.joinHousehold(home.inviteCode, owner)
    assertArrayEquals(png, repository.load(second).getOrThrow())
    repository.delete(second).getOrThrow()
  }

  @Test
  fun personalItemPhotoIsReachableByItsOwnerOnlyAndUnsharingHidesIt() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Photo home", owner)
    createItem(home.id)
    val photo = upload()
    val member = FirebaseEmulator.signInAs("photo-member")
    homes.joinHousehold(home.inviteCode, member)
    denied(repository.load(photo))
    denied(repository.delete(photo))
    denied(repository.upload(itemId, temporary.newFile().apply { writeBytes(png) }))

    FirebaseEmulator.signInAs("photo-owner")
    firestore.collection("items").document(itemId).update("sharing", "SHARED").await()
    FirebaseEmulator.signInAs("photo-member")
    assertArrayEquals(png, repository.load(photo).getOrThrow())

    FirebaseEmulator.signInAs("photo-owner")
    firestore.collection("items").document(itemId).update("sharing", "PERSONAL").await()
    FirebaseEmulator.signInAs("photo-member")
    denied(repository.load(photo))
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
  fun privateFridgePhotosAreReachableByItsCreatorOnly() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val privateHome = homes.createHousehold("Private", owner, FridgeType.PRIVATE)
    createItem(privateHome.id)
    val reference = upload()
    val outsider = FirebaseEmulator.signInAs("photo-outsider")
    // Even listed among the members by an administrator, an outsider never reaches a private
    // fridge.
    patchHousehold(
        privateHome.id,
        "memberIds",
        """{"memberIds":{"arrayValue":{"values":[{"stringValue":"$owner"},{"stringValue":"$outsider"}]}}}""")
    denied(repository.load(reference))
    denied(repository.delete(reference))
    FirebaseEmulator.signInAs("photo-owner")
    assertArrayEquals(png, repository.load(reference).getOrThrow())
    repository.delete(reference).getOrThrow()
  }

  @Test
  fun recreatedItemDoesNotExposeOldPhotoToItsNewOwner() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Photo home", owner)
    createItem(home.id, sharing = "SHARED")
    val original = upload()
    val formerMember = FirebaseEmulator.signInAs("photo-former-member")
    homes.joinHousehold(home.inviteCode, formerMember)
    assertArrayEquals(png, repository.load(original).getOrThrow())
    homes.leaveHousehold(formerMember)
    FirebaseEmulator.signInAs("photo-owner")
    firestore.collection("items").document(itemId).delete().await()
    FirebaseEmulator.signInAs("photo-former-member")
    val formerMemberHome = homes.createHousehold("Former member home", formerMember)
    createItem(formerMemberHome.id, formerMember)
    denied(repository.load(original))
    denied(repository.delete(original))
    val ownPhoto = upload()
    assertArrayEquals(png, repository.load(ownPhoto).getOrThrow())
    repository.delete(ownPhoto).getOrThrow()
  }

  @Test
  fun rulesRejectInvalidMetadataSizesOverwritesListingAndMissingItems() = runBlocking {
    createItem(HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner).id)
    val reference = upload()
    val validMetadata = photoMetadata()
    val objectRef = storage.reference.child(reference.path)
    suspend fun rejected(bytes: ByteArray, metadata: StorageMetadata) {
      val fresh = storage.reference.child("itemPhotos/$itemId/${UUID.randomUUID()}")
      denied(runCatching { fresh.putBytes(bytes, metadata).await() })
    }
    rejected(png, StorageMetadata.Builder(validMetadata).setContentType("text/plain").build())
    rejected(png, StorageMetadata.Builder().setContentType("image/png").build())
    rejected(png, photoMetadata("forged-owner"))
    rejected(byteArrayOf(), validMetadata)
    rejected(ByteArray(PersonalPhotoRepository.MAX_BYTES + 1), validMetadata)
    denied(runCatching { objectRef.putBytes(png, validMetadata).await() })
    denied(runCatching { objectRef.updateMetadata(validMetadata).await() })
    denied(runCatching { storage.reference.child("itemPhotos/$itemId").listAll().await() })
    assertTrue(
        repository.upload("missing-item", temporary.newFile().apply { writeBytes(png) }).isFailure)
    firestore.collection("items").document(itemId).delete().await()
    denied(repository.load(reference))
    denied(repository.delete(reference))
  }

  @Test
  fun cancellationActuallyCancelsSdkUpload() = runBlocking {
    createItem(HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner).id)
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
      val task = reference.putStream(it, photoMetadata())
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
    createItem(HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner).id)
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

  private suspend fun createItem(
      householdId: String,
      ownerId: String = owner,
      sharing: String = "PERSONAL"
  ) {
    firestore
        .collection("items")
        .document(itemId)
        .set(
            mapOf(
                "ownerId" to ownerId,
                "householdId" to householdId,
                "sharing" to sharing,
                "quantity" to 1,
                "status" to "ACTIVE",
                "name" to "Photo item"))
        .await()
  }

  private suspend fun upload(): PersonalPhotoReference {
    val source = temporary.newFile().apply { writeBytes(png) }
    return repository.upload(itemId, source).getOrThrow().also {
      assertArrayEquals(png, source.readBytes())
    }
  }

  @Test
  fun savedGalleryPhotoIsRereadByAnotherMemberAndDeletedBeforeItsItem() = runBlocking {
    val homes = HouseholdRepositoryFirestore(firestore)
    val home = homes.createHousehold("Gallery home", owner)
    val models = ViewModelStore()
    val files =
        object : PersonalPhotoFiles {
          override suspend fun copy(uri: String, fromCamera: Boolean) =
              temporary.newFile().apply { writeBytes(png) }

          override suspend fun delete(file: java.io.File) {
            file.delete()
          }
        }
    suspend fun fridge(user: String) =
        withContext(Dispatchers.Main) {
          FridgeViewModel(
                  FirebaseItemRepository(firestore),
                  user,
                  home.id,
                  FoodImageSelector(
                      object : FoodImageRepository {
                        override suspend fun searchByTitle(title: String): FoodImageSearchResult =
                            error("A selected photo must skip automatic lookup")
                      }),
                  BarcodeScanner { BarcodeScanResult.NotFound },
                  testProductCatalogRepository(),
                  repository,
                  files,
                  expirationPreferences =
                      ExpirationPreferencesProvider(
                          FirebaseExpirationPreferencesRepository(FirebaseEmulator.firestore)),
                  fridgeType = FridgeType.SHARED)
              .also { models.put("fridge", it) }
        }
    try {
      val creator = fridge(owner)
      withContext(Dispatchers.Main) {
        creator.openAddItemDialog()
        creator.updateName("Gallery food")
        // Personal items are invisible to other members, so share it for the second user.
        creator.updateSharing(ItemSharing.SHARED)
        creator.personalPhotos!!.select("content://gallery/photo")
      }
      withTimeout(15_000) { creator.personalPhotos!!.draft.first { it.file != null } }
      withContext(Dispatchers.Main) { creator.saveItem() }
      val saved =
          withTimeout(20_000) {
            creator.uiState.first { it.items.any { item -> item.image != null } }.items.single()
          }
      assertEquals(ItemImageOrigin.PERSONAL_PHOTO, saved.image?.origin)
      assertFalse(creator.uiState.value.isAddItemDialogOpen)
      withContext(Dispatchers.Main) { models.clear() }

      val outsider = FirebaseEmulator.signInAs("gallery-member")
      val reference = PersonalPhotoReference(checkNotNull(saved.image).reference)
      denied(repository.load(reference))
      homes.joinHousehold(home.inviteCode, outsider)
      val member = fridge(outsider)
      val reread =
          withTimeout(15_000) { member.uiState.first { it.items.isNotEmpty() } }.items.single()
      assertEquals(saved, reread)
      val bytes = member.personalPhotos!!.load(reference.path)
      assertArrayEquals(png, bytes)
      assertEquals(1, decodePersonalThumbnail(bytes, 80).width)

      withContext(Dispatchers.Main) {
        member.requestRemoval(reread.id)
        member.confirmRemoval()
      }
      withTimeout(20_000) { member.uiState.first { it.items.isEmpty() && !it.isDeleting } }
      val objects = JSONObject(emulatorRequest("GET", "")).optJSONArray("items")
      assertEquals(0, objects?.length() ?: 0)
    } finally {
      withContext(Dispatchers.Main) { models.clear() }
    }
  }

  private fun photoMetadata(ownerId: String = owner): StorageMetadata =
      StorageMetadata.Builder()
          .setContentType("image/png")
          .setCustomMetadata("itemOwnerId", ownerId)
          .build()

  private fun denied(result: Result<*>) {
    val error = result.exceptionOrNull()
    val denied =
        when (error) {
          is StorageException ->
              error.errorCode in
                  listOf(
                      StorageException.ERROR_NOT_AUTHORIZED,
                      StorageException.ERROR_NOT_AUTHENTICATED)
          is FirebaseFirestoreException ->
              error.code in
                  listOf(
                      FirebaseFirestoreException.Code.PERMISSION_DENIED,
                      FirebaseFirestoreException.Code.UNAUTHENTICATED)
          else -> false
        }
    assertTrue("Expected a permission failure, got $result", denied)
  }
}
