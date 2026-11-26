// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.staging.*
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateReceiptHistoryFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun openingHistoryBacksUpPreviouslySavedPrivateReceiptAndPhoto() {
    val owner = FirebaseEmulator.signInAs("private-backup-${UUID.randomUUID()}")
    val db = FirebaseEmulator.firestore
    val storage =
        com.google.firebase.storage.FirebaseStorage.getInstance().apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.STORAGE_PORT)
        }
    val home = runBlocking {
      com.android.sharemate.model.household
          .HouseholdRepositoryFirestore(db)
          .createHousehold(
              "Private backup", owner, com.android.sharemate.model.household.FridgeType.PRIVATE)
          .id
    }
    val id = UUID.randomUUID().toString()
    val store = AndroidInventorySubmissionStore(compose.activity, scheduleSync = false)
    val receipt =
        InventorySubmission(
            id,
            owner,
            home,
            listOf(StagedItem("milk", "Milk", 1, 200)),
            sharing = ItemSharing.PERSONAL)
    val items = FirebaseItemRepository(db)
    val photo = java.io.File(compose.activity.filesDir, "private-backup-$id.jpg")
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("receipts/inge-migros-receipt.jpg")
        .use { input -> photo.outputStream().use(input::copyTo) }
    runBlocking {
      (items.addItems(receipt) as ItemWrite.Queued).confirmation.await().getOrThrow()
      store.save(receipt)
      store.saveDetails(
          ReceiptDetails(
              id,
              owner,
              home,
              android.net.Uri.fromFile(photo).toString(),
              1,
              savedAtMillis = 2,
              title = "Recovered private receipt"))
    }
    val backup = db.collection("users").document(owner).collection("receiptBackups").document(id)
    assertFalse(
        com.google.android.gms.tasks.Tasks.await(
                backup.get(com.google.firebase.firestore.Source.SERVER))
            .exists())
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            items,
            owner,
            home,
            sharedHousehold = false,
            expenseRepository =
                object : ExpenseSubmissionRepository {
                  override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
                      error("Private receipt must not create a shared expense")
                },
            receiptCloudRepository =
                com.android.sharemate.model.receipt.FirebaseReceiptCloudRepository(
                    compose.activity, db, storage))
      }
    }
    compose.waitUntil(60_000) {
      val saved =
          com.google.android.gms.tasks.Tasks.await(
              backup.get(com.google.firebase.firestore.Source.SERVER))
      saved.getBoolean("photoReady") == true && saved.getBoolean("draft") == false
    }
    val saved =
        com.google.android.gms.tasks.Tasks.await(
            backup.get(com.google.firebase.firestore.Source.SERVER))
    assertEquals("Recovered private receipt", saved.getString("title"))
    val bytes =
        com.google.android.gms.tasks.Tasks.await(
            storage.reference.child(checkNotNull(saved.getString("photoPath"))).getBytes(5_000_000))
    assertArrayEquals(photo.readBytes(), bytes)
    assertFalse(org.json.JSONObject(saved.getString("submission")!!).has("expense"))
  }

  @Test
  fun savedPrivateReceiptShowsTotalAndConfirmPersistsAddedItemAndTitle() {
    FirebaseEmulator.auth
    FirebaseEmulator.firestore
    val owner = UUID.randomUUID().toString()
    val home = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val store = AndroidInventorySubmissionStore(compose.activity, scheduleSync = false)
    val original =
        InventorySubmission(
            id,
            owner,
            home,
            listOf(StagedItem("milk", "Milk", 1, 200)),
            sharing = ItemSharing.PERSONAL)
    runBlocking {
      store.save(original)
      store.saveDetails(
          ReceiptDetails(id, owner, home, "", 1, savedAtMillis = 2, title = "Private"))
    }
    val corrections = mutableListOf<ReceiptCorrection>()
    val items =
        object : ItemRepository by UnusedItems {
          override fun observeSubmission(submission: InventorySubmission) =
              flowOf(InventorySubmissionStatus.CONFIRMED)

          override fun addItems(submission: InventorySubmission): ItemWrite =
              error("Correction must not insert inventory again")
        }
    val expenses =
        object : ExpenseSubmissionRepository {
          override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
              error("Private receipt has no shared expense")

          override suspend fun correctReceipt(correction: ReceiptCorrection) {
            corrections += correction
          }
        }
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            items,
            owner,
            home,
            sharedHousehold = false,
            expenseRepository = expenses,
            receiptCloudRepository =
                object : ReceiptCloudRepository {
                  override fun observe(
                      ownerId: String,
                      householdId: String
                  ): Flow<List<ReceiptCloudEntry>> = emptyFlow()
                })
      }
    }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(ReceiptDashboardTestTags.card(id)).fetchSemanticsNodes().size == 1
    }
    compose.onNodeWithTag(ReceiptDashboardTestTags.card(id)).performClick()
    compose
        .onNodeWithTag(StagingTestTags.TOTAL)
        .assertIsDisplayed()
        .assertTextContains("2.00", substring = true)
    compose.onNodeWithTag(ReceiptHistoryTestTags.TITLE).performTextReplacement("Private edited")
    compose.onNodeWithTag(StagingTestTags.ADD).performScrollTo().performClick()
    compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextInput("Bread")
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextInput("3.00")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    compose
        .onNodeWithTag(StagingTestTags.TOTAL)
        .assertIsDisplayed()
        .assertTextContains("5.00", substring = true)
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(ReceiptDashboardTestTags.card(id)).fetchSemanticsNodes().size == 1
    }
    compose
        .onNodeWithTag(ReceiptDashboardTestTags.card(id))
        .assertTextContains(
            compose.activity.getString(R.string.receipt_status_saved), substring = true)
    assertEquals(1, corrections.size)
    assertNull(corrections.single().replacement.expense)
    runBlocking {
      assertEquals(2, store.loadById(owner, home, id)?.items?.size)
      assertEquals("Private edited", store.details(owner, home, id)?.title)
      assertEquals(1L, store.details(owner, home, id)?.revision)
      assertNull(store.pendingCorrection(owner, home, id))
    }
    compose.onNodeWithTag(ReceiptDashboardTestTags.card(id)).performClick()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    compose
        .onNodeWithTag(StagingTestTags.TOTAL)
        .assertIsDisplayed()
        .assertTextContains("5.00", substring = true)
  }

  private object UnusedItems : ItemRepository {
    override suspend fun getFridgeItemsFromServer(householdId: String, userId: String): List<Item> =
        error("Server inventory reads are not used in this test")

    override fun getFridgeItems(householdId: String, userId: String): Flow<List<Item>> = emptyFlow()

    override suspend fun addItem(item: Item): String? = error("No inventory write expected")

    override fun addItems(submission: InventorySubmission): ItemWrite =
        error("No inventory write expected")

    override fun observeSubmission(
        submission: InventorySubmission
    ): Flow<InventorySubmissionStatus> = emptyFlow()

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("No image update expected")

    override suspend fun deleteItem(itemId: String) = error("No item delete expected")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite =
        error("No item update expected")

    override fun setItemSharing(itemId: String, sharing: ItemSharing): ItemWrite =
        error("No sharing update expected")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("No status update expected")

    override fun deleteItemQueued(itemId: String): ItemWrite = error("No item delete expected")
  }
}
