// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.image.BarcodePhotoStore
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventorySaveFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val owner = ViewModelStore()

  @After
  fun tearDown() {
    compose.runOnIdle { owner.clear() }
  }

  @Test
  fun reviewWaitsForJournalRecoveryBeforeDisplayingOrSubmittingRetainedItems() {
    val oldRows = listOf(StagedItem("old", "Old receipt A", 1, 100))
    val newRows = listOf(StagedItem("new", "Recovered receipt B", 2, 480))
    val recovered =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc", "owner", "home", newRows, draft = true)
    val gate = CompletableDeferred<InventorySubmission?>()
    val repository = ControlledItems()
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = gate.await()

          override suspend fun save(submission: InventorySubmission) = Unit
        }
    val reader = ReceiptReader { error("No new scan expected") }
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      val scan =
          ViewModelProvider(
              compose.activity,
              viewModelFactory {
                initializer { ReceiptScanViewModel(reader, SavedStateHandle()) }
              })[ReceiptScanViewModel::class.java]
      scan.confirm(oldRows)
      save = InventorySaveViewModel(repository, store, "owner", "home")
      owner.put("save", save)
    }
    val photo =
        BarcodePhotoViewModel(
            object : BarcodePhotoStore {
              override suspend fun createCapture(): String = error("No photo expected")

              override suspend fun importImage(uri: String): String = error("No photo expected")

              override suspend fun validate(uri: String) = Unit

              override suspend fun delete(uri: String) = Unit
            },
            SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent { MaterialTheme { ReceiptScanFlow(photo, reader, save) } }
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose.onNodeWithText("Old receipt A").assertDoesNotExist()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertDoesNotExist()
    compose.runOnIdle { gate.complete(recovered) }
    compose.onNodeWithText("Recovered receipt B").assertIsDisplayed()
    compose.onNodeWithText("Old receipt A").assertDoesNotExist()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.waitUntil(TIMEOUT) { repository.calls.size == 1 }
    assertEquals(newRows, repository.calls.single().items)
    assertEquals(recovered.id, repository.calls.single().id)
  }

  @Test
  fun compactLandscapeKeepsPendingReviewAndRetryReachableAndShowsSuccessOnlyAfterAck() {
    val rows = listOf(StagedItem("milk", "Corrected milk", 2, 480))
    val submission =
        InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    val status = MutableStateFlow(InventorySubmissionStatus.PENDING)
    val ack = CompletableDeferred<Result<Unit>>()
    val repository = ControlledItems(status, ack)
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = submission

          override suspend fun save(submission: InventorySubmission) = Unit
        }
    val photoStore =
        object : BarcodePhotoStore {
          override suspend fun createCapture(): String = error("No photo capture expected")

          override suspend fun importImage(uri: String): String = error("No picker expected")

          override suspend fun validate(uri: String) = Unit

          override suspend fun delete(uri: String) = Unit
        }
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      save = InventorySaveViewModel(repository, store, "owner", "home")
      owner.put("save", save)
    }
    val photo = BarcodePhotoViewModel(photoStore, SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.requiredSize(WIDTH.dp, HEIGHT.dp)) {
          ReceiptScanFlow(photo, ReceiptReader { error("No scan expected") }, save)
        }
      }
    }
    val context = compose.activity
    compose
        .onNodeWithText(context.getString(R.string.inventory_save_pending))
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.inventory_save_success)).assertDoesNotExist()
    compose.onNodeWithText(context.getString(R.string.scan_receipt)).assertIsNotEnabled()
    compose
        .onNodeWithText(context.getString(R.string.staging_title))
        .performScrollTo()
        .performClick()
    compose.onNodeWithText("Corrected milk").assertExists()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(StagingTestTags.ADD).assertIsDisplayed().assertIsNotEnabled()
    compose.runOnIdle { status.value = InventorySubmissionStatus.MISSING }
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsDisplayed().assertIsEnabled()
    compose.onNodeWithTag(StagingTestTags.item("milk")).performScrollTo().performClick()
    // A pasted name longer than an item name may be is cut, so it can never be refused later.
    compose
        .onNodeWithTag(StagedItemEditTestTags.NAME)
        .performTextReplacement("a".repeat(TextLimits.ITEM_NAME + 1))
    compose
        .onNodeWithTag(StagedItemEditTestTags.NAME)
        .assert(hasText("a".repeat(TextLimits.ITEM_NAME)))
    compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Valid correction")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsDisplayed().performClick()
    compose.waitUntil(TIMEOUT) { repository.calls.size == 1 }
    compose
        .onNodeWithText(context.getString(R.string.inventory_save_pending))
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.inventory_save_success)).assertDoesNotExist()
    compose.runOnIdle { status.value = InventorySubmissionStatus.MISSING }
    compose
        .onNodeWithTag(InventorySaveTestTags.RETRY)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.waitUntil(TIMEOUT) { repository.calls.size == 2 }
    compose.runOnIdle { ack.complete(Result.success(Unit)) }
    compose
        .onNodeWithText(context.getString(R.string.inventory_save_success))
        .performScrollTo()
        .assertIsDisplayed()
    assertEquals(InventorySavePhase.CONFIRMED, save.state.value.phase)
    val corrected =
        InventorySubmission(
            submission.id,
            submission.ownerId,
            submission.householdId,
            listOf(rows.single().copy(name = "Valid correction")))
    assertEquals(listOf(corrected, corrected), repository.calls)
  }

  @Test
  fun scannedReceiptSavesReviewedCorrectionsAndManualItemsAndWaitsForServerSuccess() {
    val context = compose.activity
    val file = File.createTempFile("inventory-receipt", ".png", context.cacheDir)
    try {
      val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
      bitmap.recycle()
      val photoStore =
          object : BarcodePhotoStore {
            override suspend fun createCapture(): String = error("No camera expected")

            override suspend fun importImage(uri: String) = Uri.fromFile(file).toString()

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          }
      val photo = BarcodePhotoViewModel(photoStore, SavedStateHandle())
      owner.put("photo", photo)
      val ack = CompletableDeferred<Result<Unit>>()
      val repository = ControlledItems(ack = ack)
      val store =
          object : InventorySubmissionStore {
            var retained: InventorySubmission? = null

            override suspend fun load(ownerId: String, householdId: String) = retained

            override suspend fun save(submission: InventorySubmission) {
              retained = submission
            }
          }
      lateinit var save: InventorySaveViewModel
      compose.runOnIdle {
        save = InventorySaveViewModel(repository, store, "owner", "home")
        owner.put("save", save)
      }
      compose.setContent {
        MaterialTheme {
          ReceiptScanFlow(
              photo,
              ReceiptReader { listOf(ReceiptItem("Milk", 240), ReceiptItem("Bread", 310)) },
              save)
        }
      }
      compose.onNodeWithText(context.getString(R.string.scan_receipt)).performClick()
      compose.runOnIdle { photo.select("content://picker/receipt") }
      compose.waitUntil(TIMEOUT) { photo.state.value.uri != null && !photo.state.value.busy }
      compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
      compose.onNodeWithTag(StagingTestTags.item("0")).performClick()
      compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Oat milk")
      compose.onNodeWithTag(StagedItemEditTestTags.QUANTITY).performTextReplacement("3")
      compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("7.20")
      compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
      compose.onNodeWithTag(StagingTestTags.delete("1")).performClick()

      val addButton =
          compose
              .onNodeWithTag(StagingTestTags.ADD)
              .assertIsDisplayed()
              .assertTextEquals(context.getString(R.string.staging_add_item))
      val titleBounds =
          compose
              .onNodeWithText(context.getString(R.string.staging_title))
              .fetchSemanticsNode()
              .boundsInRoot
      val addBounds = addButton.fetchSemanticsNode().boundsInRoot
      assertTrue(addBounds.center.x > titleBounds.center.x)
      assertTrue(addBounds.top < titleBounds.bottom && titleBounds.top < addBounds.bottom)
      addButton.performClick()
      compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Coffee")
      compose.onNodeWithTag(StagedItemEditTestTags.QUANTITY).performTextReplacement("2")
      compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("4.60")
      compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
      compose.onNodeWithText("Coffee").assertIsDisplayed()

      compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
      compose.onNodeWithText(context.getString(R.string.inventory_save_pending)).assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.inventory_save_success))
          .assertDoesNotExist()
      val captured = repository.calls.single()
      assertEquals(2, captured.items.size)
      val added = captured.items.last()
      assertEquals(
          listOf(StagedItem("0", "Oat milk", 3, 720), StagedItem(added.id, "Coffee", 2, 460)),
          captured.items)
      assertEquals(captured, store.retained)
      compose.runOnIdle { ack.complete(Result.success(Unit)) }
      compose.onNodeWithText(context.getString(R.string.inventory_save_success)).assertIsDisplayed()
      compose.onNodeWithText(context.getString(R.string.receipt_reviewed)).assertDoesNotExist()
    } finally {
      file.delete()
    }
  }

  private class ControlledItems(
      val status: MutableStateFlow<InventorySubmissionStatus> =
          MutableStateFlow(InventorySubmissionStatus.PENDING),
      val ack: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
  ) : ItemRepository {
    val calls = mutableListOf<InventorySubmission>()

    override fun addItems(submission: InventorySubmission): ItemWrite {
      calls += submission
      status.value = InventorySubmissionStatus.PENDING
      return ItemWrite.Queued(ack)
    }

    override fun observeSubmission(submission: InventorySubmission) = status

    override fun getFridgeItems(householdId: String, userId: String) = flowOf(emptyList<Item>())

    override suspend fun addItem(item: Item): String? = error("Unexpected single-item save")

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("Unexpected image update")

    override suspend fun deleteItem(itemId: String): Boolean = error("Unexpected deletion")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite = error("Unexpected edit")

    override fun setItemSharing(itemId: String, sharing: ItemSharing): ItemWrite =
        error("Unexpected sharing update")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("Unexpected status update")

    override fun deleteItemQueued(itemId: String): ItemWrite = error("Unexpected deletion")
  }

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val TIMEOUT = 10_000L
    const val IMAGE_SIZE = 8
    const val PNG_QUALITY = 100
  }
}
