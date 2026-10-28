// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SDK persistence and rules tests; requires Auth and Firestore emulators from firebase.json.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseItemRepositoryEmulatorTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = FirebaseItemRepository(firestore)
  private lateinit var owner: String

  @Before
  fun setUp() {
    await(firestore.enableNetwork())
    FirebaseEmulator.clear()
    owner = FirebaseEmulator.signInAs("item-owner")
  }

  @After
  fun tearDown() {
    await(firestore.enableNetwork())
    await(firestore.waitForPendingWrites())
    FirebaseEmulator.clear()
  }

  @Test
  fun roundTripPartialEditStatusesAndDeletion() = runBlocking {
    val original =
        Item(
            name = "Milk",
            ownerId = owner,
            category = "Dairy",
            expirationDate = Date(1_800_000_000_000),
            quantity = 3)
    val id = checkNotNull(repository.addItem(original))
    val doc = document(id)
    assertEquals(original.copy(id = id), await(doc.get(Source.SERVER)).toItemOrNull())
    // Metadata belongs to other features and must survive ordinary item edits.
    adminPatch(id, JSONObject().put("imageUrl", field("stringValue", "https://example.org/milk")))
    await(doc.update("imageUrl", "https://example.org/milk"))
    confirm(repository.updateItem(id, ItemEdit(" Oat milk ", 2, null, null)))
    val edited =
        original.copy(
            id = id, name = "Oat milk", quantity = 2, category = null, expirationDate = null)
    val snapshot = await(doc.get(Source.SERVER))
    assertEquals(edited, snapshot.toItemOrNull())
    assertEquals("https://example.org/milk", snapshot.getString("imageUrl"))
    assertEquals(owner, snapshot.getString("ownerId"))
    assertNull(snapshot.getString("householdId"))
    val newExpiry = Date(1_900_000_000_000)
    confirm(repository.updateItem(id, ItemEdit("Oat milk", 2, "Dairy", newExpiry)))
    val dated = edited.copy(category = "Dairy", expirationDate = newExpiry)
    val datedSnapshot = await(doc.get(Source.SERVER))
    assertEquals(dated, datedSnapshot.toItemOrNull())
    assertEquals("https://example.org/milk", datedSnapshot.getString("imageUrl"))
    for (status in listOf(ItemStatus.EATEN, ItemStatus.DISCARDED, ItemStatus.ACTIVE)) {
      confirm(repository.setItemStatus(id, status))
      assertEquals(dated.copy(status = status), await(doc.get(Source.SERVER)).toItemOrNull())
      val read =
          withTimeout(10_000) {
            repository.getPrivateItems(owner).first { items ->
              items.any { it.id == id && it.status == status }
            }
          }
      assertEquals(status, read.single { it.id == id }.status)
    }
    confirm(repository.deleteItemQueued(id))
    assertEquals(404, adminRequest(id).first)
    confirm(repository.deleteItemQueued(id))
  }

  @Test
  fun legacyDocumentsLoadDefaultsAndCanBeEdited() = runBlocking {
    val id = UUID.randomUUID().toString()
    await(
        document(id)
            .set(
                mapOf(
                    "name" to "Legacy",
                    "ownerId" to owner,
                    "householdId" to null,
                    "category" to "Pantry")))
    val legacy = await(document(id).get(Source.SERVER)).toItemOrNull()
    assertEquals(Item(id, "Legacy", owner, category = "Pantry"), legacy)
    assertTrue(
        withTimeout(10_000) {
              repository.getPrivateItems(owner).first { items -> items.any { it.id == id } }
            }
            .any { it.id == id && it.quantity == 1 && it.status == ItemStatus.ACTIVE })
    confirm(repository.updateItem(id, ItemEdit("Legacy edited", 4, "Pantry", null)))
    confirm(repository.setItemStatus(id, ItemStatus.EATEN))
    assertEquals(4, await(document(id).get(Source.SERVER)).toItemOrNull()?.quantity)
  }

  @Test
  fun invalidQuantitiesStatusesAndOwnershipTransfersAreDeniedByRules() = runBlocking {
    val id = checkNotNull(repository.addItem(Item(name = "Apple", ownerId = owner)))
    val doc = document(id)
    for (quantity in listOf(0, -1, 1.5, "2", 2_147_483_648L)) {
      assertDenied(doc.update("quantity", quantity))
    }
    for (status in listOf("UNKNOWN", "eaten", 4, null)) {
      assertDenied(doc.update("status", status))
    }
    assertDenied(doc.update("ownerId", "another-user"))
    assertDenied(doc.update("householdId", "another-household"))
    assertDenied(doc.update("name", ""))
    assertTrue(repository.updateItem(id, ItemEdit("Apple", 0, null, null)) is ItemWrite.Rejected)
    assertNull(repository.addItem(Item(name = "Apple", ownerId = owner, quantity = 0)))
    assertDenied(document("invalid").set(Item(name = "Apple", ownerId = owner, quantity = -1)))
    assertEquals(1, await(doc.get(Source.SERVER)).toItemOrNull()?.quantity)
    assertEquals(ItemStatus.ACTIVE, await(doc.get(Source.SERVER)).toItemOrNull()?.status)
  }

  @Test
  fun privateOwnersSharedMembersOutsidersAndSignedOutUsersAreSeparated() = runBlocking {
    val householdRepository = HouseholdRepositoryFirestore(firestore)
    val household = householdRepository.createHousehold("Home", owner)
    val privateId = checkNotNull(repository.addItem(Item(name = "Private", ownerId = owner)))
    val sharedId =
        checkNotNull(
            repository.addItem(Item(name = "Shared", ownerId = owner, householdId = household.id)))
    assertEquals(
        listOf(privateId),
        withTimeout(10_000) {
          repository.getPrivateItems(owner).first { it.isNotEmpty() }.map(Item::id)
        })
    val member = FirebaseEmulator.signInAs("item-member")
    householdRepository.joinHousehold(household.inviteCode, member)
    confirm(repository.updateItem(sharedId, ItemEdit("Member edit", 2, "Pantry", null)))
    assertEquals(owner, await(document(sharedId).get(Source.SERVER)).getString("ownerId"))
    assertEquals(
        household.id, await(document(sharedId).get(Source.SERVER)).getString("householdId"))
    assertEquals(
        listOf(sharedId),
        withTimeout(10_000) {
          repository.getSharedItems(household.id).first { it.isNotEmpty() }.map(Item::id)
        })
    assertDenied(document(privateId).get(Source.SERVER))
    assertWriteDenied(repository.updateItem(privateId, ItemEdit("Stolen", 1, null, null)))
    assertWriteDenied(repository.deleteItemQueued(privateId))
    assertDenied(document(sharedId).update("ownerId", member))
    assertDenied(document(sharedId).update("householdId", null))
    confirm(repository.setItemStatus(sharedId, ItemStatus.EATEN))
    FirebaseEmulator.signInAs("item-outsider")
    assertDenied(document(sharedId).get(Source.SERVER))
    assertWriteDenied(repository.setItemStatus(sharedId, ItemStatus.DISCARDED))
    assertWriteDenied(repository.deleteItemQueued(sharedId))
    assertNull(repository.addItem(Item(name = "Forged", ownerId = owner)))
    assertNull(
        repository.addItem(
            Item(
                name = "Intrusion",
                ownerId = FirebaseEmulator.auth.uid!!,
                householdId = household.id)))
    FirebaseEmulator.signOut()
    assertDenied(document(privateId).get(Source.SERVER))
    assertWriteDenied(repository.setItemStatus(privateId, ItemStatus.EATEN))
    FirebaseEmulator.signInAs("item-member")
    confirm(repository.deleteItemQueued(sharedId))
    assertEquals(404, adminRequest(sharedId).first)
  }

  @Test
  fun missingUpdateFailsWithoutCreatingADocument() = runBlocking {
    val id = UUID.randomUUID().toString()
    val result = confirmation(repository.updateItem(id, ItemEdit("Missing", 1, null, null)))
    assertTrue(result.isFailure)
    assertEquals(404, adminRequest(id).first)
    confirm(repository.deleteItemQueued(id))
  }

  @Test
  fun offlineEditsAndStatusUpdateCacheThenSynchronize() = runBlocking {
    val id = checkNotNull(repository.addItem(Item(name = "Milk", ownerId = owner)))
    await(document(id).get(Source.SERVER))
    await(firestore.disableNetwork())
    try {
      val edit =
          repository.updateItem(id, ItemEdit("Offline milk", 5, "Dairy", null)) as ItemWrite.Queued
      val status = repository.setItemStatus(id, ItemStatus.EATEN) as ItemWrite.Queued
      val local =
          withTimeout(10_000) {
                repository.getPrivateItems(owner).first { items ->
                  items.any {
                    it.id == id && it.name == "Offline milk" && it.status == ItemStatus.EATEN
                  }
                }
              }
              .single { it.id == id }
      assertEquals(5, local.quantity)
      assertEquals("Dairy", local.category)
      assertTrue(await(document(id).get(Source.CACHE)).metadata.hasPendingWrites())
      assertFalse(edit.confirmation.isCompleted)
      assertFalse(status.confirmation.isCompleted)
      val server = JSONObject(adminRequest(id).second).getJSONObject("fields")
      assertEquals("Milk", server.getJSONObject("name").getString("stringValue"))
      assertEquals("ACTIVE", server.getJSONObject("status").getString("stringValue"))
      await(firestore.enableNetwork())
      confirm(edit)
      confirm(status)
      val synchronized = await(document(id).get(Source.SERVER))
      assertEquals(local, synchronized.toItemOrNull())
      assertFalse(synchronized.metadata.hasPendingWrites())
    } finally {
      await(firestore.enableNetwork())
    }
  }

  @Test
  fun offlineDeletionRemovesCachedItemBeforeServerAcknowledgement() = runBlocking {
    val id = checkNotNull(repository.addItem(Item(name = "Milk", ownerId = owner)))
    await(document(id).get(Source.SERVER))
    await(firestore.disableNetwork())
    try {
      val deletion = repository.deleteItemQueued(id) as ItemWrite.Queued
      assertFalse(await(document(id).get(Source.CACHE)).exists())
      assertTrue(
          withTimeout(10_000) { repository.getPrivateItems(owner).first { it.isEmpty() } }
              .isEmpty())
      assertFalse(deletion.confirmation.isCompleted)
      assertEquals(200, adminRequest(id).first)
      await(firestore.enableNetwork())
      confirm(deletion)
      assertEquals(404, adminRequest(id).first)
    } finally {
      await(firestore.enableNetwork())
    }
  }

  @Test
  fun offlineUnauthorizedWriteReportsServerRejectionAndRollsBack() = runBlocking {
    val id = checkNotNull(repository.addItem(Item(name = "Milk", ownerId = owner)))
    await(document(id).get(Source.SERVER))
    FirebaseEmulator.signInAs("item-outsider")
    await(firestore.disableNetwork())
    try {
      val write = repository.setItemStatus(id, ItemStatus.DISCARDED) as ItemWrite.Queued
      val optimistic = await(document(id).get(Source.CACHE))
      assertEquals(ItemStatus.DISCARDED, optimistic.toItemOrNull()?.status)
      assertTrue(optimistic.metadata.hasPendingWrites())
      assertFalse(write.confirmation.isCompleted)
      await(firestore.enableNetwork())
      assertWriteDenied(write)
      val rolledBack = await(document(id).get(Source.CACHE))
      assertEquals(ItemStatus.ACTIVE, rolledBack.toItemOrNull()?.status)
      assertFalse(rolledBack.metadata.hasPendingWrites())
      FirebaseEmulator.signInAs("item-owner")
      assertEquals(ItemStatus.ACTIVE, await(document(id).get(Source.SERVER)).toItemOrNull()?.status)
    } finally {
      await(firestore.enableNetwork())
    }
  }

  @Test
  fun sharingRoundTripsAndTransitionsPreserveOwnerAndHousehold() = runBlocking {
    val household = HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner)
    for (sharing in ItemSharing.values()) {
      val original =
          Item(name = "Milk", ownerId = owner, householdId = household.id, sharing = sharing)
      val id = checkNotNull(repository.addItem(original))
      val doc = document(id)
      val created = await(doc.get(Source.SERVER))
      assertEquals(sharing.name, created.getString("sharing"))
      assertFalse(created.contains("shared"))
      assertEquals(original.copy(id = id), created.toItemOrNull())
      for (next in listOf(ItemSharing.PERSONAL, ItemSharing.SHARED)) {
        confirm(repository.setItemSharing(id, next))
        val transitioned = await(doc.get(Source.SERVER))
        assertFalse(transitioned.contains("shared"))
        assertEquals(original.copy(id = id, sharing = next), transitioned.toItemOrNull())
      }
      confirm(repository.updateItem(id, ItemEdit("Updated", 2, null, null)))
      confirm(repository.setItemStatus(id, ItemStatus.EATEN))
      val updated = await(doc.get(Source.SERVER)).toItemOrNull()!!
      assertEquals(ItemSharing.SHARED, updated.sharing)
      assertEquals(owner, updated.ownerId)
      assertEquals(household.id, updated.householdId)
      confirm(repository.deleteItemQueued(id))
    }
  }

  @Test
  fun legacyBooleansRemainReadableAndAreRemovedOnSharingWrites() = runBlocking {
    val home = HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner)
    for (householdId in listOf(null, home.id)) {
      for (legacyBoolean in listOf(false, true)) {
        for (legacySharing in listOf(null, ItemSharing.SHARED, ItemSharing.PERSONAL)) {
          val doc = document(UUID.randomUUID().toString())
          val fields =
              mutableMapOf<String, Any?>(
                  "name" to "Legacy milk",
                  "ownerId" to owner,
                  "householdId" to householdId,
                  "shared" to legacyBoolean)
          if (legacySharing != null) fields["sharing"] = legacySharing.name
          await(doc.set(fields))
          val original = await(doc.get(Source.SERVER)).toItemOrNull()!!
          assertEquals(
              legacySharing
                  ?: if (householdId == null) ItemSharing.PERSONAL else ItemSharing.SHARED,
              original.sharing)
          for (next in listOf(ItemSharing.PERSONAL, ItemSharing.SHARED)) {
            confirm(repository.setItemSharing(doc.id, next))
            val stored = await(doc.get(Source.SERVER))
            assertEquals(next.name, stored.getString("sharing"))
            assertFalse(stored.contains("shared"))
            assertEquals(owner, stored.getString("ownerId"))
            assertEquals(householdId, stored.getString("householdId"))
            assertEquals(original.copy(sharing = next), stored.toItemOrNull())
          }
        }
      }
    }
  }

  @Test
  fun personalHouseholdItemsRemainVisibleButOnlyOwnerChangesSharing() = runBlocking {
    val households = HouseholdRepositoryFirestore(firestore)
    val home = households.createHousehold("Home", owner)
    val id =
        checkNotNull(
            repository.addItem(
                Item(
                    name = "Milk",
                    ownerId = owner,
                    householdId = home.id,
                    sharing = ItemSharing.PERSONAL)))
    // Old SDK writes may contain this contradictory derived field; it grants no authority.
    await(document(id).update("shared", true))
    val member = FirebaseEmulator.signInAs("sharing-member")
    households.joinHousehold(home.inviteCode, member)
    assertEquals(
        ItemSharing.PERSONAL, await(document(id).get(Source.SERVER)).toItemOrNull()!!.sharing)
    assertTrue(
        withTimeout(10_000) {
              repository.getSharedItems(home.id).first { it.any { item -> item.id == id } }
            }
            .any { it.id == id })
    assertWriteDenied(repository.setItemSharing(id, ItemSharing.SHARED))
    val deniedSnapshot = await(document(id).get(Source.SERVER))
    assertEquals("PERSONAL", deniedSnapshot.getString("sharing"))
    assertEquals(true, deniedSnapshot.getBoolean("shared"))
    assertEquals(owner, deniedSnapshot.getString("ownerId"))
    assertEquals(home.id, deniedSnapshot.getString("householdId"))
    assertDenied(document(id).update("sharing", com.google.firebase.firestore.FieldValue.delete()))
    assertDenied(document(id).update("ownerId", member))
    assertDenied(document(id).update("householdId", null))
    // Ordinary member edits remain allowed without changing sharing or associations.
    confirm(repository.updateItem(id, ItemEdit("Member edit", 2, null, null)))
    assertEquals(
        ItemSharing.PERSONAL, await(document(id).get(Source.SERVER)).toItemOrNull()!!.sharing)
    FirebaseEmulator.signInAs("sharing-outsider")
    assertDenied(document(id).get(Source.SERVER))
    assertWriteDenied(repository.setItemSharing(id, ItemSharing.SHARED))
    FirebaseEmulator.signOut()
    assertWriteDenied(repository.setItemSharing(id, ItemSharing.SHARED))
  }

  @Test
  fun legacySharingDefaultsAndInvalidSharingAreEnforced() = runBlocking {
    val home = HouseholdRepositoryFirestore(firestore).createHousehold("Home", owner)
    val doc = document("legacy-sharing")
    val legacy = mapOf("name" to "Milk", "ownerId" to owner, "householdId" to home.id)
    await(doc.set(legacy))
    assertEquals(ItemSharing.SHARED, await(doc.get(Source.SERVER)).toItemOrNull()!!.sharing)
    for (bad in listOf(null, "INVALID", 1L, true)) {
      assertDenied(doc.update("sharing", bad))
      assertDenied(document("invalid-sharing").set(legacy + ("sharing" to bad)))
    }
    val member = FirebaseEmulator.signInAs("legacy-sharing-member")
    HouseholdRepositoryFirestore(firestore).joinHousehold(home.inviteCode, member)
    assertWriteDenied(repository.setItemSharing(doc.id, ItemSharing.PERSONAL))
    FirebaseEmulator.signInAs("item-owner")
    confirm(repository.setItemSharing(doc.id, ItemSharing.PERSONAL))
    assertEquals(home.id, await(doc.get(Source.SERVER)).getString("householdId"))
    val standalone = document("standalone-sharing")
    await(standalone.set(mapOf("name" to "Private", "ownerId" to owner)))
    assertEquals(
        ItemSharing.PERSONAL, await(standalone.get(Source.SERVER)).toItemOrNull()!!.sharing)
    confirm(repository.setItemSharing(standalone.id, ItemSharing.SHARED))
    assertNull(await(standalone.get(Source.SERVER)).getString("householdId"))
    FirebaseEmulator.signInAs("legacy-sharing-member")
    assertDenied(standalone.get(Source.SERVER))
  }

  private fun document(id: String): DocumentReference =
      firestore.collection(FirestoreCollections.ITEMS).document(id)

  private suspend fun confirmation(write: ItemWrite): Result<Unit> =
      when (write) {
        is ItemWrite.Rejected -> Result.failure(write.cause)
        is ItemWrite.Queued -> withTimeout(20_000) { write.confirmation.await() }
      }

  private suspend fun confirm(write: ItemWrite) {
    confirmation(write).getOrThrow()
  }

  private suspend fun assertWriteDenied(write: ItemWrite) {
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (confirmation(write).exceptionOrNull() as? FirebaseFirestoreException)?.code)
  }

  private fun assertDenied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { await(task) }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure.cause as? FirebaseFirestoreException)?.code)
  }

  private fun <T> await(task: Task<T>): T = Tasks.await(task, 20, TimeUnit.SECONDS)

  private fun field(type: String, value: Any) = JSONObject().put(type, value)

  private fun adminPatch(id: String, fields: JSONObject) {
    val response =
        adminRequest(
            id,
            "PATCH",
            JSONObject().put("fields", fields).toString(),
            "?updateMask.fieldPaths=imageUrl")
    assertEquals(200, response.first)
  }

  /** Admin REST access verifies server state while this SDK's network is disabled. */
  private fun adminRequest(
      id: String,
      method: String = "GET",
      body: String? = null,
      query: String = ""
  ): Pair<Int, String> {
    val project = FirebaseApp.getInstance().options.projectId
    val connection =
        URL(
                "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}" +
                    "/v1/projects/$project/databases/(default)/documents/items/$id$query")
            .openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.requestMethod = method
      connection.setRequestProperty("Authorization", "Bearer owner")
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    } finally {
      connection.disconnect()
    }
  }
}
