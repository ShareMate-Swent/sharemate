// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.R
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptHistoryFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun twoDraftCardsOpenTheirOwnReviewedItems() {
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val store: InventorySubmissionStore = AndroidInventorySubmissionStore(compose.activity)
    val firstId = UUID.randomUUID().toString()
    val secondId = UUID.randomUUID().toString()
    val scanTime = System.currentTimeMillis()
    val photo = File(compose.activity.filesDir, "receipt-history-demo.jpg")
    InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("receipts/inge-migros-receipt.jpg")
        .use { input -> photo.outputStream().use(input::copyTo) }
    val photoUri = Uri.fromFile(photo).toString()
    runBlocking {
      store.save(
          InventorySubmission(
              firstId, owner, household, listOf(StagedItem("a", "Apple", 1, 100)), draft = true))
      store.saveDetails(ReceiptDetails(firstId, owner, household, photoUri, scanTime - 86_400_000L))
      store.save(
          InventorySubmission(
              secondId, owner, household, listOf(StagedItem("b", "Bread", 2, 300)), draft = true))
      store.saveDetails(ReceiptDetails(secondId, owner, household, photoUri, scanTime))
    }
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = UnusedItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true)
      }
    }

    val review = "receipt_card_"
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(review + secondId).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(review + firstId).assertExists()
    compose.onNodeWithTag(review + secondId).performClick()
    compose.onAllNodesWithTag(ReceiptHistoryTestTags.PHOTO_PREVIEW)[0].performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertIsDisplayed()
    saveScreenshot("receipt-photo-large.png", ReceiptHistoryTestTags.PHOTO_EXPANDED)
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertDoesNotExist()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    saveScreenshot("receipt-review.png")
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    compose.onNodeWithTag("receipt_save_draft").assertDoesNotExist()
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(review + firstId).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(review + firstId).assertExists()
    saveScreenshot("receipt-history.png")
    compose.onNodeWithTag(review + firstId).performClick()
    compose.onNodeWithText("Apple").assertIsDisplayed()
  }

  @Test
  fun savedAndPendingReceiptsShowTheirOwnStatusAndCanBeViewed() {
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val store: InventorySubmissionStore = AndroidInventorySubmissionStore(compose.activity)
    val savedId = UUID.randomUUID().toString()
    val pendingId = UUID.randomUUID().toString()
    val scanTime = System.currentTimeMillis()
    runBlocking {
      store.save(
          InventorySubmission(savedId, owner, household, listOf(StagedItem("a", "Apple", 1, 100))))
      store.saveDetails(ReceiptDetails(savedId, owner, household, "", scanTime, scanTime))
      store.save(
          InventorySubmission(
              pendingId, owner, household, listOf(StagedItem("b", "Bread", 1, 200))))
      store.saveDetails(ReceiptDetails(pendingId, owner, household, "", scanTime))
    }
    val pendingItems =
        object : ItemRepository by UnusedItems {
          override fun addItems(submission: InventorySubmission): ItemWrite =
              ItemWrite.Queued(CompletableDeferred())
        }
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = pendingItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true)
      }
    }

    val saved = compose.activity.getString(R.string.receipt_status_saved)
    val pending = compose.activity.getString(R.string.inventory_save_pending)
    val view = "receipt_card_"
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(view + savedId).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onAllNodesWithText(saved).assertCountEquals(2)
    compose.onNodeWithText(pending).assertIsDisplayed()
    compose.onNodeWithTag(view + pendingId).performClick()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    compose.onNodeWithTag(view + savedId).performClick()
    compose.onNodeWithText("Apple").assertIsDisplayed()
  }

  @Test
  fun emptyHistoryExposesScanAndBalancesActions() {
    val balancesOpened = booleanArrayOf(false)
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = UnusedItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true,
            showBalances = true,
            onOpenBalances = { balancesOpened[0] = true })
      }
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.receipt_scan_action), substring = true)
        .performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    compose.onNodeWithTag("receipt_back").performClick()
    compose.onNodeWithTag(com.android.sharemate.ui.finance.BalancesTestTags.ENTRY).performClick()
    compose.runOnIdle { assertTrue(balancesOpened[0]) }
  }

  @Test
  fun confirmingDraftReturnsToHistoryAndPersistsReviewedRows() {
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val store = AndroidInventorySubmissionStore(compose.activity)
    runBlocking { store.save(InventorySubmission(id, owner, household, rows, draft = true)) }
    val submitted = mutableListOf<InventorySubmission>()
    val repository =
        object : ItemRepository by UnusedItems {
          override fun addItems(submission: InventorySubmission): ItemWrite {
            submitted += submission
            return ItemWrite.Queued(CompletableDeferred())
          }
        }
    compose.setContent {
      MaterialTheme { ReceiptsScreen(repository, owner, household, sharedHousehold = true) }
    }
    val review = "receipt_card_$id"
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(review).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(review).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    val view = "receipt_card_$id"
    compose.waitUntil(5_000) { compose.onAllNodesWithTag(view).fetchSemanticsNodes().isNotEmpty() }
    compose.runOnIdle {
      org.junit.Assert.assertEquals(rows, submitted.single().items)
      org.junit.Assert.assertEquals(id, submitted.single().id)
    }
    runBlocking { org.junit.Assert.assertFalse(store.list(owner, household).single().draft) }
  }

  @Test
  fun rejectedReceiptCanBeRetriedFromReview() {
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val store = AndroidInventorySubmissionStore(compose.activity)
    runBlocking { store.save(InventorySubmission(id, owner, household, rows)) }
    var attempts = 0
    val status = kotlinx.coroutines.flow.MutableStateFlow(InventorySubmissionStatus.MISSING)
    val repository =
        object : ItemRepository by UnusedItems {
          override fun observeSubmission(submission: InventorySubmission) = status

          override fun addItems(submission: InventorySubmission): ItemWrite {
            attempts++
            status.value = InventorySubmissionStatus.PENDING
            return ItemWrite.Queued(CompletableDeferred())
          }
        }
    compose.setContent {
      MaterialTheme { ReceiptsScreen(repository, owner, household, sharedHousehold = true) }
    }
    val view = "receipt_card_$id"
    compose.waitUntil(5_000) { compose.onAllNodesWithTag(view).fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithTag(view).performClick()
    val retry = compose.activity.getString(R.string.household_retry)
    compose.onNodeWithText(retry).performClick()
    compose.waitUntil(5_000) { attempts == 1 }
    compose
        .onNodeWithText(compose.activity.getString(R.string.inventory_save_pending))
        .assertIsDisplayed()
    compose.runOnIdle { org.junit.Assert.assertEquals(1, attempts) }
  }

  private fun saveScreenshot(name: String, tag: String? = null) {
    val output = File(compose.activity.getExternalFilesDir(null), name)
    output.outputStream().use { stream ->
      (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag))
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
    InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand("cp ${output.absolutePath} /sdcard/Download/$name")
        .close()
  }

  private object UnusedItems : ItemRepository {
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
