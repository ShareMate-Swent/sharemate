// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventorySubmissionEmulatorTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = FirebaseItemRepository(firestore)
  private val households = HouseholdRepositoryFirestore(firestore)
  private lateinit var owner: String
  private val rows =
      listOf(StagedItem("milk", "Corrected milk", 2, 480), StagedItem("bread", "Bread", 1, null))

  @Before
  fun setUp() = runBlocking {
    firestore.enableNetwork().await()
    FirebaseEmulator.clear()
    owner = FirebaseEmulator.signInAs(OWNER_ALIAS)
  }

  @After
  fun tearDown() = runBlocking {
    firestore.enableNetwork().await()
    withTimeout(TIMEOUT) { firestore.waitForPendingWrites().await() }
    FirebaseEmulator.clear()
  }

  @Test
  fun offlineQueueWaitsForServerAndReplaysCannotOverwriteEditedOrDeletedItems() = runBlocking {
    val home = households.createHousehold("Home", owner)
    val submission = submission(home.id)
    firestore.disableNetwork().await()
    try {
      val queued = repository.addItems(submission) as ItemWrite.Queued
      assertTrue(item(submission, 0).get(Source.CACHE).await().metadata.hasPendingWrites())
      assertEquals(
          InventorySubmissionStatus.PENDING,
          withTimeout(TIMEOUT) { repository.observeSubmission(submission).first() })
      assertFalse(queued.confirmation.isCompleted)
      firestore.enableNetwork().await()
      assertTrue(withTimeout(TIMEOUT) { queued.confirmation.await() }.isSuccess)
      assertEquals(
          InventorySubmissionStatus.CONFIRMED,
          withTimeout(TIMEOUT) {
            repository.observeSubmission(submission).first {
              it == InventorySubmissionStatus.CONFIRMED
            }
          })
      val persisted = item(submission, 0).get(Source.SERVER).await().toItemOrNull()!!
      assertEquals("Corrected milk", persisted.name)
      assertEquals(2, persisted.quantity)
      assertEquals(ItemSharing.SHARED, persisted.sharing)
      assertEquals(ItemStatus.ACTIVE, persisted.status)
      assertEquals(owner, persisted.ownerId)
      assertNull(persisted.category)
      assertNull(persisted.expirationDate)
      val marker = marker(submission).get(Source.SERVER).await()
      val retained = marker.get(Fields.ROWS) as List<*>
      assertEquals(480L, (retained.first() as Map<*, *>)[Fields.PRICE])
      assertNull((retained.last() as Map<*, *>)[Fields.PRICE])
      confirm(repository.updateItem(submission.itemId(0), ItemEdit("Edited later", 5, null, null)))
      confirm(repository.deleteItemQueued(submission.itemId(1)))
      denied(repository.addItems(submission))
      assertEquals(
          "Edited later", item(submission, 0).get(Source.SERVER).await().getString(Fields.NAME))
      assertFalse(item(submission, 1).get(Source.SERVER).await().exists())
      val member = FirebaseEmulator.signInAs("inventory-member")
      households.joinHousehold(home.inviteCode, member)
      assertEquals(
          "Edited later",
          withTimeout(TIMEOUT) {
                repository.getFridgeItems(home.id, member).first {
                  it.any { row -> row.id == submission.itemId(0) }
                }
              }
              .single { it.id == submission.itemId(0) }
              .name)
      assertDenied { marker(submission).get(Source.SERVER).await() }
    } finally {
      firestore.enableNetwork().await()
    }
  }

  @Test
  fun unauthorizedDestinationsForgedOwnerAndSignedOutSubmissionAreRejectedAtomically() =
      runBlocking {
        val shared = households.createHousehold("Home", owner)
        val private = households.createHousehold("Private", owner, FridgeType.PRIVATE)
        val privateSubmission = submission(private.id)
        denied(repository.addItems(privateSubmission))
        assertFalse(item(privateSubmission, 0).get(Source.SERVER).await().exists())
        val outsider = FirebaseEmulator.signInAs("inventory-outsider")
        val foreign = InventorySubmission(UUID.randomUUID().toString(), outsider, shared.id, rows)
        firestore.disableNetwork().await()
        try {
          val queued = repository.addItems(foreign) as ItemWrite.Queued
          assertTrue(item(foreign, 0).get(Source.CACHE).await().metadata.hasPendingWrites())
          assertFalse(queued.confirmation.isCompleted)
          firestore.enableNetwork().await()
          denied(queued)
        } finally {
          firestore.enableNetwork().await()
        }
        val forged = submission(shared.id)
        denied(repository.addItems(forged))
        FirebaseEmulator.signOut()
        val signedOut = submission(shared.id)
        denied(repository.addItems(signedOut))
        FirebaseEmulator.signInAs(OWNER_ALIAS)
        for (rejected in listOf(foreign, forged, signedOut)) {
          FirebaseEmulator.signInAs(
              if (rejected.ownerId == owner) OWNER_ALIAS else "inventory-outsider")
          assertFalse(item(rejected, 0).get(Source.SERVER).await().exists())
          assertFalse(marker(rejected).get(Source.SERVER).await().exists())
        }
      }

  @Test
  fun malformedItemRejectsWholeBatchAndOnlyOwnerCanDiscoverImmutableMarkers() = runBlocking {
    val home = households.createHousehold("Home", owner)
    val invalid = submission(home.id)
    val raw = firestore.batch()
    raw.set(
        item(invalid, 0),
        Item(id = invalid.itemId(0), name = "Valid", ownerId = owner, householdId = home.id))
    raw.set(
        item(invalid, 1),
        Item(
            id = invalid.itemId(1),
            name = "Invalid",
            ownerId = owner,
            householdId = home.id,
            quantity = 0))
    raw.set(marker(invalid), data(invalid))
    assertDenied { raw.commit().await() }
    assertFalse(item(invalid, 0).get(Source.SERVER).await().exists())
    assertFalse(marker(invalid).get(Source.SERVER).await().exists())
    val badMarker = submission(home.id)
    assertDenied { marker(badMarker).set(data(badMarker) + ("unexpected" to true)).await() }
    assertDenied {
      marker(badMarker).set(data(badMarker) + (Fields.ROWS to emptyList<Any>())).await()
    }
    val good = submission(home.id)
    confirm(repository.addItems(good))
    assertDenied { marker(good).update(Fields.HOUSEHOLD, "other").await() }
    assertDenied { marker(good).delete().await() }
    assertTrue(marker(good).parent.get().await().documents.any { it.id == good.id })
    FirebaseEmulator.signInAs("marker-outsider")
    assertDenied { marker(good).parent.get().await() }
  }

  @Test
  fun maximumSupportedBatchPersistsEveryRowInOneConfirmedSubmission() = runBlocking {
    val home = households.createHousehold("Large receipt", owner)
    val submitted =
        InventorySubmission(
            UUID.randomUUID().toString(),
            owner,
            home.id,
            List(InventorySubmission.MAX_ITEMS) { StagedItem("row-$it", "Product $it", 2, 480) })
    confirm(repository.addItems(submitted))
    assertEquals(
        InventorySubmissionStatus.CONFIRMED,
        withTimeout(TIMEOUT) {
          repository.observeSubmission(submitted).first {
            it == InventorySubmissionStatus.CONFIRMED
          }
        })
    val saved =
        firestore
            .collection(FirestoreCollections.ITEMS)
            .whereEqualTo("householdId", home.id)
            // The rules reject a query that could return other members' personal items.
            .whereEqualTo("sharing", "SHARED")
            .get(Source.SERVER)
            .await()
    assertEquals(InventorySubmission.MAX_ITEMS, saved.size())
  }

  @Test
  fun markerRejects101RowsOnTheServer() = runBlocking {
    val home = households.createHousehold("Home", owner)
    val rejected = submission(home.id)
    val oversizedRows =
        List(101) { index ->
          mapOf(
              Fields.ID to "row-$index",
              Fields.NAME to "Product $index",
              Fields.QUANTITY to 1,
              Fields.PRICE to 480L)
        }

    // Write the raw marker so the client model's row limit cannot reject it first.
    assertDenied { marker(rejected).set(data(rejected) + (Fields.ROWS to oversizedRows)).await() }
    assertFalse(marker(rejected).get(Source.SERVER).await().exists())
  }

  @Test
  fun sdkRestartRecoversPersistedOfflineBatchWithoutAnotherInsertion() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val app =
        FirebaseApp.initializeApp(
            context, FirebaseApp.getInstance().options, "inventory-restart-${UUID.randomUUID()}")
    val auth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
    var db =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
        }
    try {
      auth.signInWithEmailAndPassword("$OWNER_ALIAS@example.com", PASSWORD).await()
      val home = HouseholdRepositoryFirestore(db).createHousehold("Restart", owner)
      val submitted = submission(home.id)
      db.disableNetwork().await()
      FirebaseItemRepository(db).addItems(submitted)
      assertTrue(
          db.collection(FirestoreCollections.ITEMS)
              .document(submitted.itemId(0))
              .get(Source.CACHE)
              .await()
              .metadata
              .hasPendingWrites())
      db.terminate().await()
      db =
          FirebaseFirestore.getInstance(app).apply {
            useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
          }
      db.disableNetwork().await()
      val recovered = FirebaseItemRepository(db)
      assertEquals(
          InventorySubmissionStatus.PENDING,
          withTimeout(TIMEOUT) { recovered.observeSubmission(submitted).first() })
      db.enableNetwork().await()
      assertEquals(
          InventorySubmissionStatus.CONFIRMED,
          withTimeout(TIMEOUT) {
            recovered.observeSubmission(submitted).first {
              it == InventorySubmissionStatus.CONFIRMED
            }
          })
      val saved =
          db.collection(FirestoreCollections.ITEMS)
              .whereEqualTo("householdId", home.id)
              .whereEqualTo("sharing", "SHARED")
              .get(Source.SERVER)
              .await()
      assertEquals(submitted.items.size, saved.size())
      assertEquals(
          submitted.items.indices.map(submitted::itemId).toSet(),
          saved.documents.map { it.id }.toSet())
    } finally {
      db.enableNetwork().await()
      withTimeout(TIMEOUT) { db.waitForPendingWrites().await() }
      db.terminate().await()
      db.clearPersistence().await()
      app.delete()
    }
  }

  private fun submission(home: String) =
      InventorySubmission(UUID.randomUUID().toString(), owner, home, rows)

  private fun item(submission: InventorySubmission, index: Int) =
      firestore.collection(FirestoreCollections.ITEMS).document(submission.itemId(index))

  private fun marker(submission: InventorySubmission) =
      firestore
          .collection(FirestoreCollections.USERS)
          .document(submission.ownerId)
          .collection(Fields.COLLECTION)
          .document(submission.id)

  private fun data(submission: InventorySubmission) =
      mapOf(
          Fields.ID to submission.id,
          Fields.OWNER to submission.ownerId,
          Fields.HOUSEHOLD to submission.householdId,
          Fields.ROWS to
              submission.items.map {
                mapOf(
                    Fields.ID to it.id,
                    Fields.NAME to it.name,
                    Fields.QUANTITY to it.quantity,
                    Fields.PRICE to it.priceCents)
              })

  private suspend fun confirm(write: ItemWrite) {
    assertTrue(withTimeout(TIMEOUT) { (write as ItemWrite.Queued).confirmation.await() }.isSuccess)
  }

  private suspend fun denied(write: ItemWrite) {
    val failure =
        when (write) {
          is ItemWrite.Queued ->
              withTimeout(TIMEOUT) { write.confirmation.await() }.exceptionOrNull()
          is ItemWrite.Rejected -> write.cause
        }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure as? FirebaseFirestoreException)?.code)
  }

  private suspend fun assertDenied(action: suspend () -> Unit) {
    val failure = runCatching { withTimeout(TIMEOUT) { action() } }.exceptionOrNull()
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure as? FirebaseFirestoreException)?.code)
  }

  private companion object {
    const val OWNER_ALIAS = "inventory-owner"
    const val PASSWORD = "emulator-only-password"
    const val TIMEOUT = 20_000L
  }
}
