// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.R
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.HouseholdFinanceRepository
import com.android.sharemate.model.finance.HouseholdFinanceSnapshot
import com.android.sharemate.model.finance.Settlement
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.receipt.*
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptCorrection
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptHistoryFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun configureFirebaseBeforeOpeningHistory() {
    FirebaseEmulator.auth
    FirebaseEmulator.firestore
  }

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
            sharedHousehold = true,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository =
                object : ReceiptCloudRepository {
                  override fun observe(
                      ownerId: String,
                      householdId: String
                  ): Flow<List<ReceiptCloudEntry>> = flow {
                    throw ReceiptCloudPermanentException(IllegalStateException("Access revoked"))
                  }
                })
      }
    }

    historyCard(firstId, 2).assertExists()
    compose.onNodeWithTag(ReceiptHistoryTestTags.SYNC_ERROR).assertIsDisplayed()
    compose
        .onAllNodesWithText(compose.activity.getString(R.string.receipt_sync_failed))
        .assertCountEquals(1)
    historyCard(secondId, 2).performClick()
    compose.onAllNodesWithTag(ReceiptHistoryTestTags.PHOTO_PREVIEW)[0].performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertIsDisplayed()
    saveScreenshot("receipt-photo-large.png", ReceiptHistoryTestTags.PHOTO_EXPANDED)
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).performClick()
    compose.onNodeWithTag(ReceiptHistoryTestTags.PHOTO_EXPANDED).assertDoesNotExist()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    saveScreenshot("receipt-review.png")
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    compose.onNodeWithTag("receipt_save_draft").assertDoesNotExist()
    historyCard(firstId, 2).assertExists()
    saveScreenshot("receipt-history.png")
    historyCard(firstId, 2).performClick()
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
            sharedHousehold = true,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }

    val saved = compose.activity.getString(R.string.receipt_status_saved)
    val pending = compose.activity.getString(R.string.inventory_save_pending)
    historyCard(savedId, 2).assert(hasText(saved))
    historyCard(pendingId, 2).assert(hasText(pending)).performClick()
    compose.onNodeWithText("Bread").assertIsDisplayed()
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    historyCard(savedId, 2).performClick()
    compose.onNodeWithText("Apple").assertIsDisplayed()
  }

  @Test
  fun emptyHistoryExposesScanAndBalancesActions() {
    val balancesOpened = booleanArrayOf(false)
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val ledger = MutableStateFlow(HouseholdFinanceSnapshot(household, emptyList(), emptyList()))
    val finances =
        object : HouseholdFinanceRepository {
          override fun observeHouseholdFinances(householdId: String) = ledger

          override suspend fun recordSettlement(settlement: Settlement): Settlement =
              error("No settlement expected")
        }
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = UnusedItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true,
            showBalances = true,
            financeRepository = finances,
            onOpenBalances = { balancesOpened[0] = true },
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.scan_receipt), substring = true)
        .performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    compose.onNodeWithTag("receipt_back").performClick()
    compose.onNodeWithTag(com.android.sharemate.ui.finance.BalancesTestTags.ENTRY).performClick()
    compose.runOnIdle { assertTrue(balancesOpened[0]) }
    compose.onNodeWithText("All settled up").assertIsDisplayed()
    compose.runOnIdle {
      ledger.value =
          HouseholdFinanceSnapshot(
              household,
              listOf(SharedExpense("debt", household, "other", 300, mapOf(owner to 300L))),
              emptyList())
    }
    compose.onNodeWithText("You owe 3.00 CHF").assertIsDisplayed()
    compose.runOnIdle {
      ledger.value =
          HouseholdFinanceSnapshot(
              household,
              listOf(SharedExpense("credit", household, owner, 500, mapOf("other" to 500L))),
              emptyList())
    }
    compose.onNodeWithText("You’re owed 5.00 CHF").assertIsDisplayed()
  }

  @Test
  fun receiptOptionOfTheAddButtonOpensTheReceiptPhotoOnceAndReportsItsCancel() {
    var handled = 0
    var cancelled = 0
    // Like the app, the request comes once the screen shows, and ends once it is handled.
    val requested = mutableStateOf(false)
    // Created once: new ids on each recomposition would reset the screen and its photo.
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = UnusedItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud,
            scanReceiptRequested = requested.value,
            onScanReceiptRequestHandled = {
              handled++
              requested.value = false
            },
            onScanReceiptCancelled = {
              cancelled++
              // Opened on Receipts itself: the app stays on it.
              false
            })
      }
    }
    compose.onNodeWithText(compose.activity.getString(R.string.scan_receipt)).assertIsDisplayed()
    compose.runOnIdle { requested.value = true }
    compose.onNodeWithTag(ReceiptDashboardTestTags.BACK).assertIsDisplayed().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(1, handled)
      assertEquals(1, cancelled)
    }
    // The receipts screen's own scan button is not the add button: no way back to report.
    compose.onNodeWithText(compose.activity.getString(R.string.scan_receipt)).performClick()
    compose.onNodeWithTag(ReceiptDashboardTestTags.BACK).performClick()
    compose.runOnIdle { assertEquals(1, cancelled) }
  }

  @Test
  fun receiptOptionOfTheAddButtonOpensThePhotoOnAReceiptsDetails() {
    val owner = UUID.randomUUID().toString()
    val household = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val store = AndroidInventorySubmissionStore(compose.activity)
    runBlocking { store.save(InventorySubmission(id, owner, household, rows, draft = true)) }
    var cancelled = 0
    val requested = mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            itemRepository = UnusedItems,
            userId = owner,
            householdId = household,
            sharedHousehold = true,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud,
            scanReceiptRequested = requested.value,
            onScanReceiptRequestHandled = { requested.value = false },
            onScanReceiptCancelled = {
              cancelled++
              false
            })
      }
    }
    historyCard(id).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsDisplayed()

    // The add button asks for a scan while the receipt's details are open.
    compose.runOnIdle { requested.value = true }
    compose.onNodeWithTag(ReceiptDashboardTestTags.BACK).assertIsDisplayed().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    // Opened on Receipts itself: the details are still open.
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, cancelled) }
  }

  @Test
  fun confirmingDraftReturnsToHistoryAndPersistsReviewedRows() {
    confirmDraft(sharedHousehold = true)
  }

  @Test
  fun privateDraftConfirmsWithoutExpenseSharing() {
    confirmDraft(sharedHousehold = false)
  }

  private fun confirmDraft(sharedHousehold: Boolean) {
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
      MaterialTheme {
        ReceiptsScreen(
            repository,
            owner,
            household,
            sharedHousehold = sharedHousehold,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    historyCard(id).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    historyCard(id)
    compose.runOnIdle {
      org.junit.Assert.assertEquals(rows, submitted.single().items)
      org.junit.Assert.assertEquals(id, submitted.single().id)
      org.junit.Assert.assertNull(submitted.single().expense)
      assertEquals(
          if (sharedHousehold) ItemSharing.SHARED else ItemSharing.PERSONAL,
          submitted.single().sharing)
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
      MaterialTheme {
        ReceiptsScreen(
            repository,
            owner,
            household,
            sharedHousehold = true,
            expenseRepository = UnusedReceiptExpenses,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    historyCard(id).performClick()
    val retry = compose.activity.getString(R.string.receipt_sync_retry)
    compose.onNodeWithText(retry).performClick()
    compose.waitUntil(5_000) { attempts == 1 }
    compose
        .onNodeWithText(compose.activity.getString(R.string.receipt_sync_pending))
        .assertIsDisplayed()
    compose.runOnIdle { org.junit.Assert.assertEquals(1, attempts) }
  }

  @Test
  fun realReceiptEditorAddsBarcodeProductAndKeepsItsEnteredPrice() {
    var scans = 0
    val fixture =
        WorkflowFixture(
            draft = true,
            barcodeScanner =
                com.android.sharemate.model.barcode.BarcodeScanner {
                  scans++
                  com.android.sharemate.model.barcode.BarcodeScanResult.Found("1234567890123")
                },
            catalog =
                object : com.android.sharemate.model.product.ProductCatalogRepository {
                  override suspend fun lookupByBarcode(barcode: String) =
                      com.android.sharemate.model.product.ProductLookupResult.Found(
                          com.android.sharemate.model.product.ProductDetails(
                              barcode, "Barcode oats", null))
                })
    fixture.open()
    compose.onNodeWithTag(PurchaseBarcodeTestTags.ADD).assertIsDisplayed().performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    assertEquals(0, scans)
    compose.onNodeWithTag(PurchaseBarcodeTestTags.ADD).assertIsDisplayed().performClick()
    val input = File.createTempFile("barcode-review", ".png", compose.activity.cacheDir)
    try {
      val bitmap =
          android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
      input.outputStream().use {
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
      }
      bitmap.recycle()
      val photo =
          androidx.lifecycle
              .ViewModelProvider(compose.activity)[
                  "receipt-barcode-photo_${fixture.owner}_${fixture.home}",
                  com.android.sharemate.ui.photo.PhotoPickerViewModel::class.java]
      compose.runOnIdle { photo.select(android.net.Uri.fromFile(input).toString()) }
      compose.waitUntil(10_000) { photo.state.value.selection != null && !photo.state.value.busy }
      compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(10_000) { !photo.state.value.visible }
      compose.onNodeWithText("Barcode oats").performClick()
      compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2.50")
      compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
      fixture.backAndKeep()
      val added = fixture.retained().items.single { it.name == "Barcode oats" }
      assertEquals(250L, added.priceCents)
      assertEquals(1, scans)
      fixture.open()
      compose.onNodeWithText("Barcode oats").assertIsDisplayed()
    } finally {
      input.delete()
    }
  }

  @Test
  fun draftWithoutMetadataOrProfilesCanStillKeepLocalEdits() {
    val fixture = WorkflowFixture(draft = true, metadata = false, purchaseServices = false)
    fixture.open()
    compose.onNodeWithTag(ReceiptHistoryTestTags.TITLE).performTextReplacement("Recovered draft")
    fixture.backAndKeep()
    assertEquals("Recovered draft", fixture.details().title)
    assertTrue(fixture.retained().draft)
    assertEquals("Milk", fixture.retained().items.single().name)
    assertEquals(0, fixture.recorded)
    assertTrue(fixture.details().draftParticipants.isEmpty())
  }

  @Test
  fun savedReceiptWithoutProfilesKeepsRejectedEditsLocal() {
    val fixture = WorkflowFixture(draft = false, purchaseServices = false)
    fixture.open()
    compose.onNodeWithText("Milk").performClick()
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2.50")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    fixture.backAndKeep(waitForHistory = false)
    compose.onNodeWithTag("receipt_save_draft").assertIsDisplayed()
    assertEquals(100L, fixture.retained().expense?.totalAmountCents)
    assertEquals(0, fixture.corrected)
    compose.onNodeWithTag("receipt_discard").performClick()
    fixture.awaitHistory()
    assertEquals(100L, fixture.retained().items.single().priceCents)
  }

  @Test
  fun failedDeletionKeepsReceiptOpenAndCanBeRetried() {
    val fixture = WorkflowFixture(draft = false)
    fixture.failFirstDeletion = true
    fixture.open()
    repeat(2) { attempt ->
      compose.onNodeWithContentDescription("More receipt actions").performClick()
      compose.onNodeWithText("Delete receipt").performClick()
      compose.onNodeWithText("Delete").performClick()
      compose.waitUntil(10_000) { fixture.deleted == attempt + 1 }
      if (attempt == 0) {
        compose.onNodeWithText("Milk").assertIsDisplayed()
        assertEquals(fixture.id, fixture.retained().id)
      }
    }
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText("No receipts yet").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(
        runBlocking { fixture.store.loadById(fixture.owner, fixture.home, fixture.id) } == null)
  }

  @Test
  fun editedDraftKeepsItsStatusUntilThePurchaseIsConfirmed() {
    val fixture = WorkflowFixture(draft = true)
    fixture.open()
    compose.onNodeWithTag(ReceiptHistoryTestTags.TITLE).performTextReplacement("Weekend groceries")
    fixture.backAndKeep()
    assertTrue(fixture.retained().draft)
    assertEquals("Weekend groceries", fixture.details().title)
    fixture.open()
    compose.onNodeWithTag(ReceiptHistoryTestTags.TITLE).performTextReplacement("Discarded title")
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    compose.onNodeWithTag("receipt_discard").performClick()
    fixture.awaitHistory()
    assertEquals("Weekend groceries", fixture.details().title)
    fixture.open()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.onNodeWithTag("staging_participant_${fixture.owner}").performScrollTo().performClick()
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    fixture.backAndKeep()
    assertTrue(fixture.retained().draft)
    assertEquals(setOf(fixture.owner), fixture.details().draftParticipants)
    fixture.open()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().performClick()
    fixture.awaitHistory()
    assertTrue(!fixture.retained().draft)
    assertEquals(ExpenseProgress.CONFIRMED, fixture.retained().expenseProgress)
    assertEquals(1, fixture.recorded)
  }

  @Test
  fun savedReceiptExitsUnchangedCanBeRenamedAndDeleted() {
    val fixture = WorkflowFixture(draft = false)
    fixture.open()
    compose.onNodeWithTag(StagingTestTags.BACK).performClick()
    fixture.awaitHistory()
    compose.onNodeWithTag("receipt_save_draft").assertDoesNotExist()
    fixture.open()
    compose.onNodeWithTag(ReceiptHistoryTestTags.TITLE).performTextReplacement("Renamed groceries")
    fixture.backAndKeep()
    assertEquals(1, fixture.corrected)
    assertEquals("Renamed groceries", fixture.details().title)
    fixture.open()
    compose.onNodeWithContentDescription("More receipt actions").performClick()
    compose.onNodeWithText("Delete receipt").performClick()
    compose.onNodeWithText("Cancel").performClick()
    compose.onNodeWithText("Milk").assertIsDisplayed()
    compose.onNodeWithContentDescription("More receipt actions").performClick()
    compose.onNodeWithText("Delete receipt").performClick()
    compose.onNodeWithText("Are you sure you want to delete this receipt?").assertIsDisplayed()
    compose.onNodeWithText("Delete").performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText("No receipts yet").fetchSemanticsNodes().isNotEmpty()
    }
    assertEquals(1, fixture.deleted)
    assertTrue(
        runBlocking { fixture.store.loadById(fixture.owner, fixture.home, fixture.id) } == null)
  }

  @Test
  fun savedReceiptRetriesItsCorrectionWithoutBecomingADraft() {
    val fixture = WorkflowFixture(draft = false)
    fixture.open()
    compose.onNodeWithText("Milk").performClick()
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2.50")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    compose
        .onNodeWithTag(ReceiptHistoryTestTags.TITLE)
        .performTextReplacement("Corrected groceries")
    fixture.failFirstCorrection = true
    fixture.backAndKeep(waitForHistory = false)
    compose.waitUntil(10_000) { fixture.corrected == 1 }
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertIsDisplayed().performClick()
    fixture.awaitHistory()
    assertEquals(2, fixture.corrected)
    assertEquals(250L, fixture.retained().expense?.totalAmountCents)
    assertEquals("Corrected groceries", fixture.details().title)
    assertTrue(!fixture.retained().draft)
  }

  @Test
  fun savedReceiptRejectsZeroTotalAndCanSaveAValidCorrection() {
    val fixture = WorkflowFixture(draft = false)
    fixture.open()
    compose.onNodeWithText("Milk").performClick()
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("0.00")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    fixture.backAndKeep(waitForHistory = false)
    compose.onNodeWithTag("receipt_save_draft").assertIsDisplayed()
    assertEquals(0, fixture.corrected)
    androidx.test.espresso.Espresso.pressBack()
    compose.onNodeWithText("Milk").performClick()
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("3.00")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().performClick()
    fixture.awaitHistory()
    assertEquals(1, fixture.corrected)
    assertEquals(300L, fixture.retained().expense?.totalAmountCents)
  }

  @Test
  fun newScanCanExitEmptyKeepAPhotoDraftOrDiscardItsReviewedItems() {
    val fixture = WorkflowFixture(draft = true, seed = false)
    val scanLabel = compose.activity.getString(R.string.scan_receipt)
    compose.onNodeWithText(scanLabel).performClick()
    compose.onNodeWithTag("receipt_back").performClick()
    compose.onNodeWithText("No receipts yet").assertIsDisplayed()
    compose.onNodeWithTag("receipt_save_draft").assertDoesNotExist()
    val directory = File(compose.activity.cacheDir, "test-fixtures").apply { mkdirs() }
    val input = File(directory, "workflow-${fixture.id}.jpg")
    val sourceUri =
        androidx.core.content.FileProvider.getUriForFile(
            compose.activity, "${compose.activity.packageName}.test-fixtures", input)
    InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("receipts/inge-migros-receipt.jpg")
        .use { source -> input.outputStream().use(source::copyTo) }
    try {
      val photo =
          androidx.lifecycle
              .ViewModelProvider(compose.activity)
              .get(
                  "receipt-photo_${fixture.owner}_${fixture.home}",
                  PhotoPickerViewModel::class.java)
      fun selectPhoto() {
        compose.onNodeWithText(scanLabel).performClick()
        compose.runOnIdle { photo.select(sourceUri.toString()) }
        compose.waitUntil(10_000) { photo.state.value.selection != null && !photo.state.value.busy }
      }
      selectPhoto()
      compose.onNodeWithTag("receipt_back").performClick()
      compose.onNodeWithTag("receipt_save_draft").performClick()
      compose.waitUntil(20_000) { !photo.state.value.visible }
      val drafts = runBlocking { fixture.store.list(fixture.owner, fixture.home) }
      assertEquals(1, drafts.size)
      assertTrue(drafts.single().draft)
      val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
      try {
        input.outputStream().use { blank.compress(Bitmap.CompressFormat.PNG, 100, it) }
      } finally {
        blank.recycle()
      }
      selectPhoto()
      compose.onNodeWithTag("receipt_back").performClick()
      compose.onNodeWithTag("receipt_save_draft").performClick()
      compose.waitUntil(20_000) { !photo.state.value.visible }
      val kept = runBlocking { fixture.store.list(fixture.owner, fixture.home) }
      assertEquals(2, kept.size)
      assertTrue(kept.any { it.items.isEmpty() })
      InstrumentationRegistry.getInstrumentation()
          .context
          .assets
          .open("receipts/inge-migros-receipt.jpg")
          .use { source -> input.outputStream().use(source::copyTo) }
      selectPhoto()
      compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(StagingTestTags.BACK).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithText("Save as draft").assertIsDisplayed()
      compose.onNodeWithTag("receipt_discard").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText(scanLabel).fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(
          kept.map { it.id },
          runBlocking { fixture.store.list(fixture.owner, fixture.home) }.map { it.id })
      selectPhoto()
      compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(StagingTestTags.BACK).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithTag("receipt_save_draft").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText(scanLabel).fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(3, runBlocking { fixture.store.list(fixture.owner, fixture.home) }.size)
    } finally {
      input.delete()
    }
  }

  private fun historyCard(id: String, count: Int = 1): SemanticsNodeInteraction {
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("receipt_history_list").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("receipt_history_list").performScrollToIndex(0)
    compose.waitUntil(10_000) {
      compose
          .onAllNodes(hasTestTag("receipt_filter_0") and hasText(count.toString()))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose
        .onNodeWithTag("receipt_history_list")
        .performScrollToNode(hasTestTag("receipt_card_$id"))
    return compose.onNodeWithTag("receipt_card_$id").assertIsDisplayed()
  }

  private inner class WorkflowFixture(
      draft: Boolean,
      seed: Boolean = true,
      metadata: Boolean = true,
      purchaseServices: Boolean = true,
      barcodeScanner: com.android.sharemate.model.barcode.BarcodeScanner? = null,
      catalog: com.android.sharemate.model.product.ProductCatalogRepository? = null,
  ) {
    val owner = UUID.randomUUID().toString()
    val home = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val store = AndroidInventorySubmissionStore(compose.activity)
    var recorded = 0
    var corrected = 0
    var failFirstCorrection = false
    var deleted = 0
    var failFirstDeletion = false

    init {
      if (seed)
          runBlocking {
            val expense =
                if (draft) null else SharedExpense(id, home, owner, 100, mapOf(owner to 100L))
            store.save(
                InventorySubmission(
                    id,
                    owner,
                    home,
                    listOf(StagedItem("milk", "Milk", 1, 100)),
                    draft,
                    expense,
                    !draft,
                    if (draft) ExpenseProgress.QUEUED else ExpenseProgress.CONFIRMED))
            if (metadata)
                store.saveDetails(
                    ReceiptDetails(
                        id, owner, home, "", System.currentTimeMillis(), title = "Groceries"))
          }
      val items =
          object : ItemRepository by UnusedItems {
            override fun addItems(submission: InventorySubmission): ItemWrite =
                ItemWrite.Queued(CompletableDeferred(Result.success(Unit)))

            override fun observeSubmission(submission: InventorySubmission) =
                kotlinx.coroutines.flow.flowOf(InventorySubmissionStatus.CONFIRMED)
          }
      val expenses =
          object : ExpenseSubmissionRepository {
            override suspend fun recordExpense(expense: SharedExpense): SharedExpense {
              recorded++
              return expense
            }

            override suspend fun correctReceipt(correction: ReceiptCorrection) {
              corrected++
              if (failFirstCorrection && corrected == 1)
                  throw java.io.IOException("Connection lost")
            }

            override suspend fun deleteReceipt(householdId: String, id: String) {
              deleted++
              if (failFirstDeletion && deleted == 1) throw java.io.IOException("Offline")
            }
          }
      val profiles =
          object : UserProfileRepository {
            override suspend fun savePublicProfile(profile: UserProfile) = Unit

            override fun observeMembers(householdId: String) =
                kotlinx.coroutines.flow.flowOf(listOf(UserProfile(owner, "Alex")))
          }
      compose.setContent {
        MaterialTheme {
          ReceiptsScreen(
              items,
              owner,
              home,
              true,
              barcodeScanner = barcodeScanner,
              productCatalogRepository = catalog,
              expenseRepository = expenses,
              receiptCloudRepository = UnusedReceiptCloud,
              profileRepository = profiles.takeIf { purchaseServices })
        }
      }
    }

    fun awaitHistory() {
      historyCard(id)
    }

    fun open() {
      awaitHistory()
      compose.onNodeWithTag("receipt_card_$id").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(ReceiptHistoryTestTags.TITLE).fetchSemanticsNodes().isNotEmpty()
      }
      compose.waitUntil(10_000) {
        compose
            .onAllNodes(hasTestTag(StagingTestTags.ADD) and isEnabled())
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
    }

    fun backAndKeep(waitForHistory: Boolean = true) {
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithText("Keep changes").assertIsDisplayed()
      compose.onNodeWithTag("receipt_save_draft").performClick()
      if (waitForHistory) awaitHistory()
    }

    fun retained() = runBlocking { checkNotNull(store.loadById(owner, home, id)) }

    fun details() = runBlocking { checkNotNull(store.details(owner, home, id)) }
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
