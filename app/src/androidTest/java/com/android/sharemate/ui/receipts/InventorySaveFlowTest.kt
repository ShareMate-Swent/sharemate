// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.image.AndroidTemporaryPhotoStore
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.model.item.*
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventorySaveFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val owner = ViewModelStore()

  @Before
  fun configureFirebaseBeforeOpeningHistory() {
    FirebaseEmulator.auth
    FirebaseEmulator.firestore
  }

  private fun seedHistory(submission: InventorySubmission) {
    kotlinx.coroutines.runBlocking {
      val store =
          com.android.sharemate.model.staging.AndroidInventorySubmissionStore(compose.activity)
      store.save(submission)
      store.saveDetails(
          com.android.sharemate.model.staging.ReceiptDetails(
              submission.id,
              submission.ownerId,
              submission.householdId,
              "",
              System.currentTimeMillis()))
    }
  }

  private fun openHistoryReceipt(id: String) {
    compose.waitUntil(TIMEOUT) {
      compose.onAllNodesWithTag("receipt_card_$id").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("receipt_card_$id").performClick()
  }

  @Test
  fun confirmingReviewedPurchaseRetainsSelectedSplitAndReturnsToReceipts() {
    val items = ControlledItems()
    val store =
        object : InventorySubmissionStore {
          var retained =
              InventorySubmission(
                  "12345678-1234-1234-1234-123456789abc",
                  "owner",
                  "home",
                  listOf(StagedItem("milk", "Milk", 2, 1001)),
                  draft = true)

          override suspend fun load(ownerId: String, householdId: String) = retained

          override suspend fun save(submission: InventorySubmission) {
            retained = submission
          }
        }
    val recorded = CompletableDeferred<SharedExpense>()
    val expenses =
        object : ExpenseSubmissionRepository {
          override suspend fun recordExpense(expense: SharedExpense): SharedExpense {
            recorded.complete(expense)
            return expense
          }
        }
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      save = InventorySaveViewModel(items, store, "owner", "home", true, expenses)
      owner.put("save", save)
    }
    val photo =
        PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent {
      MaterialTheme {
        ReceiptScanFlow(
            photo,
            ReceiptReader { error("No scan") },
            save,
            purchaseReview =
                PurchaseReview.forUser("owner", mapOf("owner" to "Alice", "bob" to "Bob")))
      }
    }
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("staging_participant_owner").performScrollTo().performClick()
    compose.onNodeWithTag("staging_participant_bob").performScrollTo().performClick()
    compose
        .onNodeWithTag("staging_confirm_purchase")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.waitUntil(TIMEOUT) { items.calls.size == 1 }
    compose.onNodeWithText(compose.activity.getString(R.string.scan_receipt)).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(mapOf("bob" to 501L, "owner" to 500L), store.retained.expense?.shares)
      assertEquals("owner", store.retained.expense?.paidBy)
      assertTrue(!recorded.isCompleted)
      items.status.value = InventorySubmissionStatus.CONFIRMED
    }
    compose.waitUntil(TIMEOUT) { recorded.isCompleted }
    compose
        .onNodeWithText(compose.activity.getString(R.string.purchase_save_success))
        .assertIsDisplayed()
    compose.runOnIdle { assertEquals(ExpenseProgress.CONFIRMED, store.retained.expenseProgress) }
  }

  @Test
  fun memberLoadingKeepsInventoryAvailableAndResolvesDisplayNameEmailAndUid() {
    val profiles = CompletableDeferred<List<UserProfile>>()
    val repository =
        object : UserProfileRepository {
          override suspend fun savePublicProfile(profile: UserProfile) = Unit

          override fun observeMembers(householdId: String) =
              kotlinx.coroutines.flow.flow { emit(profiles.await()) }
        }
    val submission =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            draft = true)
    seedHistory(submission)
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            ControlledItems(),
            "owner",
            "home",
            true,
            expenseRepository =
                object : ExpenseSubmissionRepository {
                  override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
                      error("No submission")
                },
            profileRepository = repository,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    openHistoryReceipt(submission.id)
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose
        .onNodeWithText(compose.activity.getString(R.string.purchase_members_loading))
        .assertIsDisplayed()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsNotEnabled()
    compose.runOnIdle {
      profiles.complete(
          listOf(
              UserProfile("owner", displayName = "Alice"),
              UserProfile("bob", email = "bob@example.org"),
              UserProfile("charlie")))
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.purchase_members_loading))
        .assertDoesNotExist()
    compose.onNodeWithTag("staging_payer_owner").performScrollTo().assertIsSelected()
    compose.onAllNodesWithText("Alice").assertCountEquals(2)
    compose.onAllNodesWithText("bob@example.org").assertCountEquals(2)
    compose.onAllNodesWithText("charlie").assertCountEquals(2)
  }

  @Test
  fun listenerFailureAndRetryKeepDraftSelectionsUntilFreshMembershipArrives() {
    val updates =
        kotlinx.coroutines.channels.Channel<List<UserProfile>>(
            kotlinx.coroutines.channels.Channel.UNLIMITED)
    val profiles =
        object : UserProfileRepository {
          override suspend fun savePublicProfile(profile: UserProfile) = Unit

          override fun observeMembers(householdId: String) =
              kotlinx.coroutines.flow.flow {
                for (members in updates) {
                  check(members.isNotEmpty()) { "listener failed" }
                  emit(members)
                }
              }
        }
    val submission =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            draft = true)
    seedHistory(submission)
    updates.trySend(listOf(UserProfile("owner"), UserProfile("bob")))
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            ControlledItems(),
            "owner",
            "home",
            true,
            expenseRepository =
                object : ExpenseSubmissionRepository {
                  override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
                      error("No submission")
                },
            profileRepository = profiles,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    openHistoryReceipt(submission.id)
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.onNodeWithTag("staging_participant_bob").performScrollTo().performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsEnabled()
    compose.runOnIdle { updates.trySend(emptyList()) }
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("staging_payer_owner").performScrollTo().assertIsSelected()
    compose.onNodeWithTag("staging_participant_bob").performScrollTo().assertIsOn()
    compose.onNodeWithTag("purchase_members_retry").performScrollTo().performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("staging_participant_bob").performScrollTo().assertIsOn()
    compose.runOnIdle { updates.trySend(listOf(UserProfile("owner"), UserProfile("bob"))) }
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsEnabled()
    compose.onNodeWithTag("staging_participant_bob").performScrollTo().assertIsOn()
    updates.close()
  }

  @Test
  fun recoveredDraftKeepsInventoryAvailableAndDefaultsPayerAfterLoading() {
    val review = mutableStateOf(PurchaseReview.forUser("owner", emptyMap()))
    showPurchase(
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            draft = true)) {
          review.value
        }
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsEnabled().performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performScrollTo().assertIsNotEnabled()
    compose.runOnIdle {
      review.value = PurchaseReview.forUser("owner", mapOf("owner" to "Alice", "bob" to "Bob"))
    }
    compose.onNodeWithTag("staging_payer_owner").performScrollTo().assertIsSelected()
  }

  @Test
  fun retainedPurchaseDisplaysImmutableSplitAndFinancialStatus() {
    val id = "12345678-1234-1234-1234-123456789abc"
    val expense = SharedExpense(id, "home", "owner", 1000, mapOf("owner" to 500L, "bob" to 500L))
    showPurchase(
        InventorySubmission(
            id, "owner", "home", listOf(StagedItem("milk", "Milk", 2, 1000)), expense = expense)) {
          PurchaseReview.forUser("owner", mapOf("owner" to "Alice", "bob" to "Bob"))
        }
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose
        .onNodeWithTag("staging_participant_bob")
        .performScrollTo()
        .assertIsOn()
        .assertIsNotEnabled()
    compose.onNodeWithTag("staging_share_bob").assertIsDisplayed()
    compose.onNodeWithTag("staging_purchase_error").assertDoesNotExist()
    val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
    File(compose.activity.getExternalFilesDir(null), "purchase-recovery.png").outputStream().use {
      screenshot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.purchase_save_success))
        .assertDoesNotExist()
  }

  @Test
  fun retainedPurchaseStillShowsUidAndExactShareWhenMemberNamesAreUnavailable() {
    val id = "12345678-1234-1234-1234-123456789abc"
    showPurchase(
        InventorySubmission(
            id,
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            expense = SharedExpense(id, "home", "owner", 1000, mapOf("bob" to 1000L)))) {
          PurchaseReview.forUser("owner", emptyMap())
        }
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose
        .onNodeWithTag("staging_payer_owner")
        .performScrollTo()
        .assertIsSelected()
        .assertIsNotEnabled()
    compose
        .onNodeWithTag("staging_participant_bob")
        .performScrollTo()
        .assertIsOn()
        .assertIsNotEnabled()
    compose
        .onNodeWithTag("staging_share_bob")
        .assertTextEquals(compose.activity.getString(R.string.staging_price, "10.00"))
    compose.onAllNodesWithText("bob").assertCountEquals(2)
  }

  @Test
  fun memberListenerFailureOffersWorkingRetry() {
    var attempts = 0
    val profiles =
        object : UserProfileRepository {
          override suspend fun savePublicProfile(profile: UserProfile) = Unit

          override fun observeMembers(householdId: String) =
              kotlinx.coroutines.flow.flow<List<UserProfile>> {
                if (attempts++ == 0) error("offline")
                emit(listOf(UserProfile("owner", displayName = "Alice")))
              }
        }
    val expenses =
        object : ExpenseSubmissionRepository {
          override suspend fun recordExpense(expense: SharedExpense) =
              error("No submission expected")
        }
    val submission =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            "member-retry-owner",
            "member-retry-home",
            listOf(StagedItem("milk", "Milk", 1, 100)),
            draft = true)
    seedHistory(submission)
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            ControlledItems(),
            "member-retry-owner",
            "member-retry-home",
            true,
            expenseRepository = expenses,
            profileRepository = profiles,
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    openHistoryReceipt(submission.id)
    compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    compose.waitUntil(TIMEOUT) {
      compose.onAllNodesWithTag("purchase_members_retry").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("purchase_members_retry").performScrollTo().performClick()
    compose.onNodeWithTag("purchase_members_retry").assertDoesNotExist()
    compose.runOnIdle { assertEquals(2, attempts) }
  }

  @Test
  fun unavailableProfilesAndPrivateHouseholdsKeepInventoryEntryAvailable() {
    val shared = mutableStateOf(false)
    val profilesAvailable = mutableStateOf(false)
    val profiles =
        object : UserProfileRepository {
          override suspend fun savePublicProfile(profile: UserProfile) = Unit

          override fun observeMembers(householdId: String) =
              flowOf(listOf(UserProfile("owner", displayName = "Alice")))
        }
    val expenses =
        object : ExpenseSubmissionRepository {
          override suspend fun recordExpense(expense: SharedExpense): SharedExpense =
              error("No purchase expected")
        }
    compose.setContent {
      MaterialTheme {
        ReceiptsScreen(
            ControlledItems(),
            "services-owner",
            "services-home",
            shared.value,
            expenseRepository = expenses,
            profileRepository = profiles.takeIf { profilesAvailable.value },
            receiptCloudRepository = UnusedReceiptCloud)
      }
    }
    val app = compose.activity
    compose.onNodeWithText(app.getString(R.string.scan_receipt)).assertIsEnabled()
    compose.onNodeWithText(app.getString(R.string.receipt_scan_purchase)).assertDoesNotExist()
    compose.onNodeWithTag("purchase_members_retry").assertDoesNotExist()
    compose.runOnIdle { shared.value = true }
    compose.onNodeWithText(app.getString(R.string.scan_receipt)).assertIsEnabled()
    compose
        .onNodeWithContentDescription(app.getString(R.string.receipt_more_actions))
        .assertDoesNotExist()
    compose.onNodeWithText(app.getString(R.string.purchase_members_loading)).assertDoesNotExist()
    compose.runOnIdle { profilesAvailable.value = true }
    compose.onNodeWithText(app.getString(R.string.scan_receipt)).assertIsEnabled()
    compose.runOnIdle { shared.value = false }
    compose.onNodeWithText(app.getString(R.string.scan_receipt)).assertIsEnabled()
    compose.onNodeWithText(app.getString(R.string.receipt_scan_purchase)).assertDoesNotExist()
    compose.onNodeWithTag("purchase_members_retry").assertDoesNotExist()
  }

  @Test
  fun deferredPurchaseCanBeReopenedAndSetAsideFromTheReviewScreen() {
    val id = "12345678-1234-1234-1234-123456789abc"
    val retained =
        InventorySubmission(
            id,
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            expense = SharedExpense(id, "home", "owner", 1000, mapOf("owner" to 1000L)),
            inventoryConfirmed = true,
            expenseProgress = ExpenseProgress.REJECTED)
    var active: InventorySubmission? = null
    val archived = mutableListOf(retained)
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = active

          override suspend fun save(submission: InventorySubmission) {
            active = submission
            archived.remove(submission)
          }

          override suspend fun deferred(ownerId: String, householdId: String) = archived.toList()

          override suspend fun setAside(submission: InventorySubmission) {
            archived.add(submission)
            active = null
          }
        }
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      save = InventorySaveViewModel(ControlledItems(), store, "owner", "home")
      owner.put("save", save)
    }
    val photo =
        PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent {
      MaterialTheme { ReceiptScanFlow(photo, ReceiptReader { error("No scan") }, save) }
    }
    compose.onNodeWithTag("purchase_recover_deferred").assertIsEnabled().performClick()
    compose.onNodeWithTag("purchase_recover_deferred").assertDoesNotExist()
    compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).performScrollTo().performClick()
    compose.onNodeWithTag("purchase_set_aside").performScrollTo().performClick()
    compose.onNodeWithTag("purchase_recover_deferred").assertIsEnabled()
    compose.runOnIdle {
      assertEquals(listOf(retained), archived)
      assertEquals(null, active)
    }
    compose.runOnIdle { assertTrue(save.confirm(listOf(StagedItem("bread", "Bread", 1, 200)))) }
    compose.onNodeWithTag("purchase_recover_deferred").assertIsNotEnabled()
    compose.runOnIdle { assertEquals(listOf(retained), archived) }
  }

  private fun showPurchase(submission: InventorySubmission, review: () -> PurchaseReview) {
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = submission

          override suspend fun save(submission: InventorySubmission) = Unit
        }
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      save = InventorySaveViewModel(ControlledItems(), store, "owner", "home")
      owner.put("save", save)
    }
    val photo =
        PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent {
      MaterialTheme {
        ReceiptScanFlow(photo, ReceiptReader { error("No scan") }, save, purchaseReview = review())
      }
    }
  }

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
                initializer {
                  ReceiptScanViewModel(
                      reader, SavedStateHandle(mapOf("receiptReviewPending" to true)))
                }
              })[ReceiptScanViewModel::class.java]
      scan.confirm(oldRows)
      save = InventorySaveViewModel(repository, store, "owner", "home")
      owner.put("save", save)
    }
    val photo =
        PhotoPickerViewModel(
            object : TemporaryPhotoStore {
              override suspend fun createCapture(): String = error("No photo expected")

              override suspend fun importImage(uri: String): String = error("No photo expected")

              override suspend fun validate(uri: String) = Unit

              override suspend fun delete(uri: String) = Unit
            },
            SavedStateHandle())
    owner.put("photo", photo)
    compose.setContent { MaterialTheme { ReceiptScanFlow(photo, reader, save) } }
    compose.onNodeWithTag(StagingTestTags.BACK).assertIsDisplayed()
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
        object : TemporaryPhotoStore {
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
    val photo = PhotoPickerViewModel(photoStore, SavedStateHandle())
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
    // The fixed total leaves less room in this compact viewport; the item can start off-screen.
    compose
        .onNodeWithTag(StagingTestTags.LIST)
        .performScrollToNode(hasTestTag(StagingTestTags.item("milk")))
    compose.onNodeWithText("Corrected milk").assertIsDisplayed()
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(StagingTestTags.LIST).performScrollToNode(hasTestTag(StagingTestTags.ADD))
    compose.onNodeWithTag(StagingTestTags.ADD).assertIsDisplayed().assertIsNotEnabled()
    compose.runOnIdle { status.value = InventorySubmissionStatus.MISSING }
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsDisplayed().assertIsEnabled()
    compose
        .onNodeWithTag(StagingTestTags.LIST)
        .performScrollToNode(hasTestTag(StagingTestTags.item("milk")))
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
          object : TemporaryPhotoStore {
            override suspend fun createCapture(): String = error("No camera expected")

            override suspend fun importImage(uri: String) = Uri.fromFile(file).toString()

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          }
      val photo = PhotoPickerViewModel(photoStore, SavedStateHandle())
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
      compose.waitUntil(TIMEOUT) {
        photo.state.value.selection?.uri != null && !photo.state.value.busy
      }
      compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
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
              .assertTextContains(context.getString(R.string.staging_add_item), substring = true)
      val itemBounds =
          compose.onNodeWithTag(StagingTestTags.item("0")).fetchSemanticsNode().boundsInRoot
      val addBounds = addButton.fetchSemanticsNode().boundsInRoot
      val continueBounds =
          compose.onNodeWithTag(StagingTestTags.CONFIRM).fetchSemanticsNode().boundsInRoot
      assertTrue(addBounds.top >= itemBounds.bottom)
      assertTrue(addBounds.bottom <= continueBounds.top)
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

    override suspend fun getFridgeItemsFromServer(householdId: String, userId: String): List<Item> =
        error("Server inventory reads are not used in this test")

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
