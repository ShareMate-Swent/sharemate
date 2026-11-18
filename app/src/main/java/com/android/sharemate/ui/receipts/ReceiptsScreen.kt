// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import com.android.sharemate.R
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.receipt.ReceiptPhotoReader
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity
import com.android.sharemate.ui.finance.BalancesTestTags
import com.android.sharemate.ui.household.HouseholdMembersViewModel
import com.android.sharemate.ui.household.HouseholdTopBar
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.staging.PurchasePreview
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.PurchaseReviewContent
import com.android.sharemate.ui.staging.StagingScreen
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.ui.staging.StagingViewModel
import com.android.sharemate.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Lets the user scan a receipt and review its items. Saving reviewed items to the fridge requires a
 * shared household.
 *
 * @param itemRepository Repository for submitting reviewed items and observing server confirmation.
 * @param userId ID of the signed-in user who owns the submission.
 * @param householdId ID of the current household.
 * @param sharedHousehold Whether the current household allows saving reviewed items to its fridge.
 */
@Composable
fun ReceiptsScreen(
    itemRepository: ItemRepository,
    userId: String,
    householdId: String,
    sharedHousehold: Boolean,
    showBalances: Boolean = false,
    onOpenBalances: () -> Unit = {},
    expenseRepository: ExpenseSubmissionRepository? = null,
    profileRepository: UserProfileRepository? = null,
    barcodeScanner: BarcodeScanner? = null,
    productCatalogRepository: ProductCatalogRepository? = null,
) {
  val context = LocalContext.current.applicationContext
  val reader = remember(context) { ReceiptPhotoReader(context) }
  val barcode: PurchaseBarcodeViewModel? =
      if (barcodeScanner != null && productCatalogRepository != null) {
        viewModel(
            key = "purchase-barcode_${userId}_$householdId",
            factory =
                viewModelFactory {
                  initializer {
                    PurchaseBarcodeViewModel(
                        barcodeScanner, productCatalogRepository, createSavedStateHandle())
                  }
                })
      } else null
  val barcodePhoto: BarcodePhotoViewModel =
      viewModel(
          key = "purchase-barcode-photo_${userId}_$householdId",
          factory =
              viewModelFactory {
                initializer {
                  BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
                }
              })
  val photo: BarcodePhotoViewModel =
      viewModel(
          key = "receipt-photo",
          factory =
              viewModelFactory {
                initializer {
                  BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
                }
              })
  val save: InventorySaveViewModel =
      viewModel(
          key = "inventory-save_${userId}_$householdId",
          factory =
              viewModelFactory {
                initializer {
                  InventorySaveViewModel(
                      itemRepository,
                      AndroidInventorySubmissionStore(context, legacyActive = true),
                      userId,
                      householdId,
                      sharedHousehold,
                      expenseRepository)
                }
              })
  val memberModel: HouseholdMembersViewModel? =
      if (sharedHousehold && profileRepository != null) {
        viewModel(
            key = "receipt-members_${userId}_$householdId",
            factory =
                viewModelFactory {
                  initializer { HouseholdMembersViewModel(profileRepository, householdId, userId) }
                })
      } else null
  val memberState = memberModel?.state?.collectAsStateWithLifecycle()?.value
  val members =
      if (memberState == null) emptyMap()
      else memberState.members.associate { it.uid to (it.displayName ?: it.email ?: it.uid) }
  Column {
    if (memberState?.isLoading == true) Text(stringResource(R.string.purchase_members_loading))
    if (memberState?.hasError == true) {
      Text(stringResource(R.string.household_members_error))
      Button(
          onClick = { memberModel.retry() },
          modifier = Modifier.testTag("purchase_members_retry")) {
            Text(stringResource(R.string.household_retry))
          }
    }
    ReceiptScanFlow(
        photo,
        reader,
        save,
        sharedHousehold,
        showBalances,
        onOpenBalances,
        if (sharedHousehold && expenseRepository != null)
            PurchaseReview.forUser(userId, members)
                .copy(
                    membersAvailable =
                        memberState != null && !memberState.isLoading && !memberState.hasError)
        else null,
        barcode,
        barcodePhoto)
  }
}

@Composable
internal fun ReceiptScanFlow(
    photo: BarcodePhotoViewModel,
    showBalances: Boolean = false,
    onOpenBalances: () -> Unit = {},
    reader: ReceiptReader
) {
  ReceiptScanFlow(photo, reader, null, showBalances = showBalances, onOpenBalances = onOpenBalances)
}

@Composable
internal fun ReceiptScanFlow(
    photo: BarcodePhotoViewModel,
    reader: ReceiptReader,
    save: InventorySaveViewModel?,
    sharedHousehold: Boolean = true,
    showBalances: Boolean = false,
    onOpenBalances: () -> Unit = {},
    purchaseReview: PurchaseReview? = null,
    barcode: PurchaseBarcodeViewModel? = null,
    barcodePhoto: BarcodePhotoViewModel? = null,
) {
  val nav = rememberNavController()
  val scope = rememberCoroutineScope()
  var openBarcode by rememberSaveable { mutableStateOf(false) }
  val activity = LocalContext.current.findActivity()
  DisposableEffect(photo) {
    onDispose { if (activity?.isChangingConfigurations != true) photo.discard() }
  }
  DisposableEffect(barcode, barcodePhoto) {
    onDispose {
      if (activity?.isChangingConfigurations != true) {
        barcode?.cancelScan()
        barcodePhoto?.discard()
      }
    }
  }
  val scan: ReceiptScanViewModel =
      viewModel(
          factory =
              viewModelFactory {
                initializer { ReceiptScanViewModel(reader, createSavedStateHandle()) }
              })

  val reviewed by scan.reviewed.collectAsState()
  val pending by scan.needsReview.collectAsState()
  val deferred = save?.deferred?.collectAsStateWithLifecycle()?.value.orEmpty()
  val saveState = save?.state?.collectAsStateWithLifecycle()?.value
  LaunchedEffect(saveState?.submission?.id) {
    saveState?.submission?.let { scan.confirm(it.items) }
    if (saveState?.phase == InventorySavePhase.READY && saveState.submission == null) {
      nav.popBackStack(RECEIPTS_ROUTE, false)
    }
  }
  LaunchedEffect(pending) {
    if (pending) {
      nav.navigate(REVIEW_ROUTE) { launchSingleTop = true }
      scan.reviewOpened()
    }
  }
  NavHost(nav, startDestination = RECEIPTS_ROUTE) {
    composable(RECEIPTS_ROUTE) {
      Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
            Text(
                stringResource(R.string.receipts_title),
                modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary)
            Button(
                enabled = saveState?.canStartScan != false,
                onClick = {
                  photo.reset()
                  photo.open()
                }) {
                  Text(stringResource(R.string.scan_receipt))
                }
            if (barcode != null && barcodePhoto != null) {
              Button(
                  enabled = sharedHousehold && saveState?.canStartScan != false,
                  modifier = Modifier.testTag(PurchaseBarcodeTestTags.START),
                  onClick = {
                    scope.launch {
                      try {
                        val draft = saveState?.submission?.takeIf { it.draft }
                        if (save == null || draft != null || save.beginNewScan(emptyList())) {
                          scan.confirm(
                              draft?.items
                                  ?: if (save == null) scan.currentItems() else emptyList())
                          openBarcode = true
                          nav.navigate(REVIEW_ROUTE) { launchSingleTop = true }
                        }
                      } catch (cancelled: CancellationException) {
                        throw cancelled
                      } catch (_: Exception) {
                        // The coordinator exposes journal failure through its recovery feedback.
                      }
                    }
                  }) {
                    Text(stringResource(R.string.purchase_barcode_start))
                  }
            }
            deferred.forEach { retained ->
              Button(
                  onClick = { save?.recoverDeferred(retained.id) },
                  enabled = saveState?.canStartScan == true && saveState.submission?.draft != true,
                  modifier = Modifier.testTag("purchase_recover_deferred")) {
                    Text(
                        stringResource(
                            R.string.purchase_recover_deferred,
                            retained.items.joinToString { it.name }))
                  }
            }
            if (reviewed && (save == null || saveState?.submission != null)) {
              if (save == null) Text(stringResource(R.string.receipt_reviewed))
              Button(onClick = { nav.navigate(REVIEW_ROUTE) }) {
                Text(stringResource(R.string.staging_title))
              }
            }
            if (showBalances) {
              Button(
                  onClick = onOpenBalances, modifier = Modifier.testTag(BalancesTestTags.ENTRY)) {
                    Text(stringResource(R.string.balances_title))
                  }
            }
            InventorySaveFeedback(
                saveState,
                sharedHousehold,
                onRetry = { save?.retry() },
                onSetAside = { save?.setAside() })
          }
      BarcodePhotoFlow(
          photo,
          receipt = true,
          confirmationError =
              stringResource(
                  if (saveState?.phase == InventorySavePhase.RECOVERY_FAILED)
                      R.string.inventory_save_recovery_failed
                  else R.string.receipt_unreadable),
          onImageConfirmed = { uri ->
            scan.read(uri) { items -> if (save != null) check(save.beginNewScan(items)) }
          })
    }
    composable(REVIEW_ROUTE) { entry ->
      var retainingEdits by rememberSaveable { mutableStateOf(false) }
      val recovering =
          saveState?.let {
            it.phase == InventorySavePhase.LOADING ||
                (it.phase == InventorySavePhase.RECOVERY_FAILED && it.submission == null)
          } == true
      if (recovering) {
        // SavedStateHandle may contain an older receipt than the durable journal. Do not create
        // the editable review ViewModel until its authoritative payload has been recovered.
        BackHandler(enabled = retainingEdits) {}
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          HouseholdTopBar(
              title = stringResource(R.string.staging_title),
              onBack = { if (!retainingEdits) nav.popBackStack() },
              backTestTag = StagingTestTags.BACK)
          InventorySaveFeedback(
              saveState,
              sharedHousehold,
              onRetry = { save?.retry() },
              onSetAside = { save?.setAside() })
        }
        return@composable
      }
      val staging: StagingViewModel =
          viewModel(
              viewModelStoreOwner = entry,
              key =
                  "$REVIEW_MODEL_KEY:${saveState?.submission?.id ?: UNSUBMITTED_REVIEW}:${saveState?.submission?.expense != null}",
              factory =
                  viewModelFactory {
                    initializer {
                      StagingViewModel(
                          saveState?.submission?.items ?: scan.currentItems(),
                          purchaseReview.takeIf { saveState?.submission?.expense == null })
                    }
                  })
      LaunchedEffect(staging, purchaseReview?.members, purchaseReview?.membersAvailable) {
        purchaseReview?.let { staging.updatePurchaseMembers(it.members, it.membersAvailable) }
      }
      val stagingState by staging.state.collectAsStateWithLifecycle()
      val barcodePhotoState = barcodePhoto?.state?.collectAsStateWithLifecycle()?.value
      val barcodeError = barcode?.error?.collectAsStateWithLifecycle()?.value
      LaunchedEffect(staging, barcode, barcodePhoto) {
        if (openBarcode && barcode != null && barcodePhoto != null) {
          openBarcode = false
          barcode.startScan()
          barcodePhoto.reset()
          barcodePhoto.open()
        }
      }
      val editable =
          sharedHousehold &&
              saveState?.canConfirm != false &&
              (save == null || saveState?.submission != null)
      val leaveReview: () -> Unit = leave@{
        if (saveState?.phase == InventorySavePhase.SUBMITTING) return@leave
        barcode?.cancelScan()
        scope.launch {
          try {
            // Keep this entry's editable rows alive if writing the journal fails.
            retainingEdits = true
            if (!editable ||
                save == null ||
                saveState?.canStartScan != true ||
                save.beginNewScan(stagingState.items)) {
              scan.confirm(stagingState.items)
              nav.popBackStack()
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // Retain the editable rows until journal recovery succeeds.
          }
        }
      }
      BackHandler { leaveReview() }
      if (barcode != null && barcodePhoto != null) {
        BarcodePhotoFlow(
            barcodePhoto,
            confirmationError = stringResource(barcodeError.messageResource()),
            onImageConfirmed = { uri ->
              barcode.read(uri)?.let { item -> if (editable) staging.appendBarcode(item) }
            })
      }
      StagingScreen(
          staging,
          onConfirm = { confirmed ->
            if (save == null || save.confirm(confirmed)) {
              scan.confirm(confirmed)
              nav.popBackStack()
            }
          },
          onBack = leaveReview,
          actionsEnabled = editable && barcodePhotoState?.busy != true,
          onConfirmPurchase =
              purchaseReview?.let { review ->
                { purchase ->
                  if (review.membersAvailable &&
                      save?.confirmPurchase(purchase, review.members) == true) {
                    scan.confirm(purchase.items)
                    nav.popBackStack()
                  }
                }
              },
          statusContent = {
            if (barcode != null && barcodePhoto != null) {
              Text(stringResource(R.string.purchase_barcode_price_hint))
              Button(
                  enabled =
                      editable && stagingState.draft == null && barcodePhotoState?.busy != true,
                  modifier = Modifier.testTag(PurchaseBarcodeTestTags.ADD),
                  onClick = {
                    barcode.startScan()
                    barcodePhoto.reset()
                    barcodePhoto.open()
                  }) {
                    Text(stringResource(R.string.purchase_barcode_add))
                  }
              barcodeError?.let {
                Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error)
              }
            }
            saveState?.submission?.expense?.let { expense ->
              val names =
                  (expense.shares.keys + expense.paidBy).associateWith {
                    purchaseReview?.members?.get(it) ?: it
                  }
              PurchaseReviewContent(
                  PurchasePreview(expense.totalAmountCents, expense.shares),
                  PurchaseReview(names, expense.paidBy, expense.shares.keys),
                  {},
                  {},
                  enabled = false)
            }
            InventorySaveFeedback(
                saveState,
                sharedHousehold,
                onRetry = { save?.retry() },
                onSetAside = { save?.setAside() })
          })
    }
  }
}

/** Stable Compose test tags for the inventory save status and retry button. */
object InventorySaveTestTags {
  const val STATUS = "inventory_save_status"
  const val RETRY = "inventory_save_retry"
}

@Composable
internal fun InventorySaveFeedback(
    state: InventorySaveState?,
    sharedHousehold: Boolean,
    onSetAside: () -> Unit = {},
    onRetry: () -> Unit,
) {
  if (state?.phase == InventorySavePhase.PENDING &&
      state.submission?.inventoryConfirmed == true &&
      state.submission.expenseProgress == ExpenseProgress.REJECTED) {
    Button(onClick = onSetAside, modifier = Modifier.testTag("purchase_set_aside")) {
      Text(stringResource(R.string.purchase_set_aside))
    }
  }
  val message =
      when {
        !sharedHousehold -> R.string.inventory_save_shared_only
        state?.submission?.expense != null && state.submission.inventoryConfirmed ->
            when (state.submission.expenseProgress) {
              ExpenseProgress.CONFIRMED -> R.string.purchase_save_success
              ExpenseProgress.REJECTED -> R.string.purchase_save_rejected
              ExpenseProgress.UNKNOWN -> R.string.purchase_save_unknown
              ExpenseProgress.QUEUED -> R.string.purchase_save_pending
            }
        state == null || state.phase == InventorySavePhase.READY -> null
        state.phase == InventorySavePhase.LOADING -> R.string.inventory_save_loading
        state.phase == InventorySavePhase.SUBMITTING -> R.string.inventory_save_submitting
        state.phase == InventorySavePhase.PENDING -> R.string.inventory_save_pending
        state.phase == InventorySavePhase.CONFIRMED -> R.string.inventory_save_success
        state.phase == InventorySavePhase.RECOVERY_FAILED -> R.string.inventory_save_recovery_failed
        else -> R.string.inventory_save_failed
      }
  message?.let {
    Text(
        stringResource(it, InventorySubmission.MAX_ITEMS),
        Modifier.testTag(InventorySaveTestTags.STATUS))
  }
  if (state?.phase == InventorySavePhase.FAILED &&
      state.submission != null &&
      !state.invalidInput ||
      state?.phase == InventorySavePhase.RECOVERY_FAILED ||
      state?.submission?.expenseProgress in
          setOf(ExpenseProgress.UNKNOWN, ExpenseProgress.REJECTED)) {
    Button(onClick = onRetry, modifier = Modifier.testTag(InventorySaveTestTags.RETRY)) {
      Text(stringResource(R.string.household_retry))
    }
  }
}

private const val RECEIPTS_ROUTE = "receipts"
private const val REVIEW_ROUTE = "review"
private const val REVIEW_MODEL_KEY = "receipt-review"
private const val UNSUBMITTED_REVIEW = "unsubmitted"

object PurchaseBarcodeTestTags {
  const val START = "purchase_barcode_start"
  const val ADD = "purchase_barcode_add"
}

private fun PurchaseBarcodeError?.messageResource(): Int =
    when (this) {
      PurchaseBarcodeError.UNREADABLE -> R.string.barcode_lookup_unreadable
      PurchaseBarcodeError.NOT_FOUND -> R.string.barcode_lookup_not_found
      PurchaseBarcodeError.NO_DETAILS -> R.string.barcode_lookup_no_details
      else -> R.string.barcode_lookup_failed
    }
