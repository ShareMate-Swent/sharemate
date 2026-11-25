// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.receipts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.receipt.AndroidReceiptPhotoStore
import com.android.sharemate.model.receipt.ReceiptPhotoReader
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.ui.barcode.BarcodePhotoPicker
import com.android.sharemate.ui.finance.BalancesTestTags
import com.android.sharemate.ui.household.HouseholdMembersViewModel
import com.android.sharemate.ui.household.HouseholdTopBar
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.photo.DiscardPhotoOnDispose
import com.android.sharemate.ui.photo.OnPhotoPickerCancelled
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerLabels
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.findActivity
import com.android.sharemate.ui.photo.photoPickerViewModel
import com.android.sharemate.ui.staging.PurchasePreview
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.PurchaseReviewContent
import com.android.sharemate.ui.staging.StagingScreen
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.ui.staging.StagingViewModel
import com.android.sharemate.ui.theme.Dimens
import java.text.DateFormat
import java.util.Date
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
 * @param scanReceiptRequested Whether the add button of the bottom bar asked for a receipt scan,
 *   which this screen then starts like its own scan button.
 * @param onScanReceiptRequestHandled Called once the requested scan is started.
 * @param onScanReceiptCancelled Called when the user leaves the requested scan's photo without
 *   confirming one, so the app can go back to where the user was. Returns whether the app leaves
 *   Receipts for that; if not, the screen shows again.
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
    scanReceiptRequested: Boolean = false,
    onScanReceiptRequestHandled: () -> Unit = {},
    onScanReceiptCancelled: () -> Boolean = { false },
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
  val barcodePhoto = photoPickerViewModel("purchase-barcode-photo_${userId}_$householdId")
  val photo = photoPickerViewModel("receipt-photo_${userId}_$householdId")
  val history: ReceiptHistoryViewModel =
      viewModel(
          key = "receipt-history_${userId}_$householdId",
          factory =
              viewModelFactory {
                initializer {
                  ReceiptHistoryViewModel(
                      itemRepository,
                      AndroidInventorySubmissionStore(context, legacyActive = false),
                      AndroidReceiptPhotoStore(context),
                      userId,
                      householdId)
                }
              })
  val purchaseSave: InventorySaveViewModel? =
      if (expenseRepository != null) {
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
      } else null
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
      memberState?.members?.associate { it.uid to (it.displayName ?: it.email ?: it.uid) }.orEmpty()
  var purchaseMode by rememberSaveable(userId, householdId) { mutableStateOf(false) }
  // Whether the photo shown was opened by the add button, until it is confirmed or cancelled.
  var scanFromAddButton by rememberSaveable(userId, householdId) { mutableStateOf(false) }
  // The screen stays hidden under a photo that the add button opened, until it is confirmed: the
  // user did not open Receipts, and a cancel goes back to their tab while the photo fades out.
  // Not saved, so the screen shows again when the tab is reopened later.
  var hiddenUnderAddButtonScan by remember(userId, householdId) { mutableStateOf(false) }
  LaunchedEffect(scanReceiptRequested, purchaseMode) {
    if (!scanReceiptRequested) return@LaunchedEffect
    // Leave the purchase first: its flow discards the photo when it closes.
    if (purchaseMode) {
      purchaseMode = false
    } else {
      photo.reset()
      photo.open()
      scanFromAddButton = true
      hiddenUnderAddButtonScan = true
      onScanReceiptRequestHandled()
    }
  }
  val photoConfirmed = photo.state.collectAsStateWithLifecycle().value.confirmed
  LaunchedEffect(photoConfirmed) {
    if (photoConfirmed) {
      scanFromAddButton = false
      hiddenUnderAddButtonScan = false
    }
  }
  OnPhotoPickerCancelled(photo) {
    if (scanFromAddButton) {
      scanFromAddButton = false
      // Stays hidden while the app leaves, so the fading photo does not uncover Receipts.
      if (!onScanReceiptCancelled()) hiddenUnderAddButtonScan = false
    }
  }
  key(userId, householdId) {
    Box(Modifier.alpha(if (hiddenUnderAddButtonScan) 0f else 1f)) {
      if (purchaseMode && purchaseSave != null) {
        Column {
          Button(onClick = { purchaseMode = false }) {
            Text(stringResource(R.string.receipts_title))
          }
          if (memberState?.isLoading == true)
              Text(stringResource(R.string.purchase_members_loading))
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
              purchaseSave,
              sharedHousehold,
              showBalances,
              onOpenBalances,
              if (sharedHousehold)
                  PurchaseReview.forUser(userId, members)
                      .copy(
                          membersAvailable =
                              memberState != null &&
                                  !memberState.isLoading &&
                                  !memberState.hasError)
              else null,
              barcode,
              barcodePhoto)
        }
      } else {
        ReceiptHistoryFlow(
            photo,
            reader,
            history,
            sharedHousehold,
            showBalances,
            onOpenBalances,
            onPurchase =
                if (purchaseSave != null) {
                  { purchaseMode = true }
                } else null)
      }
    }
  }
}

private val receiptPhotoLabels =
    PhotoPickerLabels(
        R.string.scan_receipt, R.string.receipt_photo_preview, R.string.receipt_photo_choose)

@Composable
internal fun ReceiptHistoryFlow(
    photo: PhotoPickerViewModel,
    reader: ReceiptReader,
    history: ReceiptHistoryViewModel,
    sharedHousehold: Boolean,
    showBalances: Boolean,
    onOpenBalances: () -> Unit,
    onPurchase: (() -> Unit)?,
) {
  val nav = rememberNavController()
  var expandedPhotoUri by remember { mutableStateOf<String?>(null) }
  DiscardPhotoOnDispose(photo)
  val receipts by history.receipts.collectAsStateWithLifecycle()
  val editFailed by history.error.collectAsStateWithLifecycle()
  val scan: ReceiptScanViewModel =
      viewModel(
          factory =
              viewModelFactory {
                initializer { ReceiptScanViewModel(reader, createSavedStateHandle()) }
              })
  val newReceiptId by history.scanToReview.collectAsStateWithLifecycle()
  LaunchedEffect(newReceiptId) {
    newReceiptId?.let { id ->
      nav.navigate("receipt/$id") { launchSingleTop = true }
      history.scanReviewOpened(id)
    }
  }
  NavHost(nav, startDestination = RECEIPTS_ROUTE) {
    composable(RECEIPTS_ROUTE) {
      LazyVerticalGrid(
          columns = GridCells.Fixed(2),
          contentPadding = PaddingValues(Dimens.spacingXs),
          horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
          verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                Text(
                    stringResource(R.string.receipts_title),
                    modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
                    style = MaterialTheme.typography.headlineMedium)
                Button(
                    onClick = {
                      photo.reset()
                      photo.open()
                    }) {
                      Text(stringResource(R.string.scan_receipt))
                    }
                onPurchase?.let { open ->
                  OutlinedButton(onClick = open) {
                    Text(stringResource(R.string.receipt_scan_purchase))
                  }
                }
                if (editFailed) {
                  Text(stringResource(R.string.receipt_edit_failed))
                  Button(onClick = history::reload) {
                    Text(stringResource(R.string.household_retry))
                  }
                }
                if (showBalances)
                    Button(
                        onClick = onOpenBalances,
                        modifier = Modifier.testTag(BalancesTestTags.ENTRY)) {
                          Text(stringResource(R.string.balances_title))
                        }
              }
            }
            items(receipts, key = { it.submission.id }) { receipt ->
              Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Dimens.spacingXs)) {
                  receipt.details?.photoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.receipt_photo_preview),
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(ReceiptLayout.CardPhotoHeight)
                                .clickable { expandedPhotoUri = uri }
                                .testTag(ReceiptHistoryTestTags.PHOTO_PREVIEW),
                        contentScale = ContentScale.Fit)
                  }
                  receipt.details?.let { details ->
                    Text(DateFormat.getDateInstance().format(Date(details.scannedAtMillis)))
                  }
                  Text(stringResource(receipt.phase.labelResource()))
                  receipt.details?.savedAtMillis?.let {
                    Text(DateFormat.getDateInstance().format(Date(it)))
                  }
                  OutlinedButton(
                      onClick = { nav.navigate("receipt/${receipt.submission.id}") },
                      modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (receipt.submission.draft) R.string.receipt_review_items
                                else R.string.receipt_view_receipt))
                      }
                }
              }
            }
          }
      PhotoPickerDialog(
          photo,
          labels = receiptPhotoLabels,
          confirmationError = stringResource(R.string.receipt_unreadable),
          onImageConfirmed = { selection ->
            scan.read(selection.uri) { items -> history.retainScan(selection.uri, items) }
          })
    }
    composable("receipt/{id}") { entry ->
      val id = entry.arguments?.getString("id")
      val receipt = receipts.firstOrNull { it.submission.id == id }
      if (receipt != null) {
        var awaitingConfirmation by remember(entry) { mutableStateOf(false) }
        LaunchedEffect(receipt.phase, awaitingConfirmation) {
          if (awaitingConfirmation && receipt.phase == InventorySavePhase.PENDING) {
            nav.popBackStack()
          }
        }
        val staging: StagingViewModel =
            viewModel(
                viewModelStoreOwner = entry,
                factory =
                    viewModelFactory { initializer { StagingViewModel(receipt.submission.items) } })
        val stagingState by staging.state.collectAsStateWithLifecycle()
        LaunchedEffect(receipt.submission.id, stagingState.items) {
          if (receipt.submission.draft && stagingState.items != receipt.submission.items) {
            history.updateDraft(receipt.submission.id, stagingState.items)
          }
        }
        val savedReview by history.savedReview.collectAsStateWithLifecycle()
        DisposableEffect(entry) { onDispose { history.clearSavedReview() } }
        LaunchedEffect(savedReview) {
          if (savedReview == receipt.submission.id) {
            history.clearSavedReview()
            nav.popBackStack()
          }
        }
        val leaveReview = {
          if (receipt.submission.draft) {
            history.saveAndLeave(receipt.submission.id, staging.state.value.items)
          } else {
            nav.popBackStack()
          }
          Unit
        }
        BackHandler(onBack = leaveReview)
        StagingScreen(
            staging,
            onConfirm = { items ->
              if (sharedHousehold && receipt.submission.draft) {
                awaitingConfirmation = true
                history.confirm(receipt.submission.id, items)
              }
            },
            onBack = leaveReview,
            actionsEnabled = sharedHousehold && receipt.submission.draft,
            statusContent = {
              receipt.details?.photoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.receipt_photo_preview),
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(ReceiptLayout.ReviewPhotoHeight)
                            .clickable { expandedPhotoUri = uri }
                            .testTag(ReceiptHistoryTestTags.PHOTO_PREVIEW))
              }
              Text(stringResource(receipt.phase.labelResource()))
              if (editFailed) Text(stringResource(R.string.receipt_edit_failed))
              if (receipt.phase == InventorySavePhase.FAILED) {
                Button(onClick = { history.retry(receipt.submission.id) }) {
                  Text(stringResource(R.string.household_retry))
                }
              }
            })
      }
    }
  }
  expandedPhotoUri?.let { uri ->
    Dialog(
        onDismissRequest = { expandedPhotoUri = null },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
          Box(
              Modifier.fillMaxSize()
                  .background(MaterialTheme.colorScheme.scrim)
                  .clickable { expandedPhotoUri = null }
                  .testTag(ReceiptHistoryTestTags.PHOTO_EXPANDED)) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.receipt_photo_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
              }
        }
  }
}

/** Photo dimensions shared by retained receipt surfaces. */
private object ReceiptLayout {
  val CardPhotoHeight = 200.dp
  val ReviewPhotoHeight = 160.dp
}

private fun InventorySavePhase.labelResource(): Int =
    when (this) {
      InventorySavePhase.READY -> R.string.receipt_status_draft
      InventorySavePhase.CONFIRMED -> R.string.receipt_status_saved
      InventorySavePhase.FAILED -> R.string.inventory_save_failed
      else -> R.string.inventory_save_pending
    }

/** Stable semantics for opening a retained receipt photo. */
object ReceiptHistoryTestTags {
  const val PHOTO_PREVIEW = "receipt_photo_preview"
  const val PHOTO_EXPANDED = "receipt_photo_expanded"
}

@Composable
internal fun ReceiptScanFlow(
    photo: PhotoPickerViewModel,
    showBalances: Boolean = false,
    onOpenBalances: () -> Unit = {},
    reader: ReceiptReader
) {
  ReceiptScanFlow(photo, reader, null, showBalances = showBalances, onOpenBalances = onOpenBalances)
}

@Composable
internal fun ReceiptScanFlow(
    photo: PhotoPickerViewModel,
    reader: ReceiptReader,
    save: InventorySaveViewModel?,
    sharedHousehold: Boolean = true,
    showBalances: Boolean = false,
    onOpenBalances: () -> Unit = {},
    purchaseReview: PurchaseReview? = null,
    barcode: PurchaseBarcodeViewModel? = null,
    barcodePhoto: PhotoPickerViewModel? = null,
) {
  val nav = rememberNavController()
  val scope = rememberCoroutineScope()
  var openBarcode by rememberSaveable { mutableStateOf(false) }
  val activity = LocalContext.current.findActivity()
  DiscardPhotoOnDispose(photo)
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
      PhotoPickerDialog(
          photo,
          labels = receiptPhotoLabels,
          confirmationError =
              stringResource(
                  if (saveState?.phase == InventorySavePhase.RECOVERY_FAILED)
                      R.string.inventory_save_recovery_failed
                  else R.string.receipt_unreadable),
          onImageConfirmed = { selection ->
            scan.read(selection.uri) { items -> if (save != null) check(save.beginNewScan(items)) }
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
        BarcodePhotoPicker(
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
