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
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
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

    val review = compose.activity.getString(R.string.receipt_review_items)
    compose.waitUntil(5_000) { compose.onAllNodesWithText(review).fetchSemanticsNodes().size == 2 }
    compose.onAllNodesWithText(review).assertCountEquals(2)
    compose.onAllNodesWithTag(ReceiptHistoryTestTags.PHOTO_PREVIEW)[0].performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertIsDisplayed()
    saveScreenshot("receipt-photo-large.png", ReceiptHistoryTestTags.PHOTO_EXPANDED)
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertDoesNotExist()
    compose.onAllNodesWithText(review)[0].performClick()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    saveScreenshot("receipt-review.png")
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    compose.onAllNodesWithText(review).assertCountEquals(2)
    saveScreenshot("receipt-history.png")
    compose.onAllNodesWithText(review)[1].performClick()
    compose.onNodeWithText("Apple").assertIsDisplayed()
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
