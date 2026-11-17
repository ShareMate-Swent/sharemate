// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.receipt.AndroidReceiptPhotoStore
import com.android.sharemate.model.receipt.ReceiptPhotoReader
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity
import com.android.sharemate.ui.finance.BalancesTestTags
import com.android.sharemate.ui.household.HouseholdTopBar
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.staging.StagingScreen
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.ui.staging.StagingViewModel
import com.android.sharemate.ui.theme.Dimens
import java.text.DateFormat
import java.util.Date
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
    onOpenBalances: () -> Unit = {}
) {
  val context = LocalContext.current.applicationContext
  val reader = remember(context) { ReceiptPhotoReader(context) }
  val photo: BarcodePhotoViewModel =
      viewModel(
          key = "receipt-photo_${userId}_$householdId",
          factory =
              viewModelFactory {
                initializer {
                  BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
                }
              })
  val history: ReceiptHistoryViewModel =
      viewModel(
          key = "receipt-history_${userId}_$householdId",
          factory =
              viewModelFactory {
                initializer {
                  ReceiptHistoryViewModel(
                      itemRepository,
                      AndroidInventorySubmissionStore(context),
                      AndroidReceiptPhotoStore(context),
                      userId,
                      householdId)
                }
              })
  key(userId, householdId) {
    ReceiptHistoryFlow(photo, reader, history, sharedHousehold, showBalances, onOpenBalances)
  }
}

@Composable
private fun ReceiptHistoryFlow(
    photo: BarcodePhotoViewModel,
    reader: ReceiptReader,
    history: ReceiptHistoryViewModel,
    sharedHousehold: Boolean,
    showBalances: Boolean,
    onOpenBalances: () -> Unit,
) {
  val nav = rememberNavController()
  val activity = LocalContext.current.findActivity()
  DisposableEffect(photo) {
    onDispose { if (activity?.isChangingConfigurations != true) photo.discard() }
  }
  val receipts by history.receipts.collectAsStateWithLifecycle()
  val editFailed by history.error.collectAsStateWithLifecycle()
  val scan: ReceiptScanViewModel =
      viewModel(
          factory =
              viewModelFactory {
                initializer { ReceiptScanViewModel(reader, createSavedStateHandle()) }
              })
  var newReceiptId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(newReceiptId) {
    newReceiptId?.let { nav.navigate("receipt/$it") }
    newReceiptId = null
  }
  NavHost(nav, startDestination = RECEIPTS_ROUTE) {
    composable(RECEIPTS_ROUTE) {
      Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
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
            if (editFailed) {
              Text(stringResource(R.string.receipt_edit_failed))
              Button(onClick = { history.viewModelScope.launch { history.refresh() } }) {
                Text(stringResource(R.string.household_retry))
              }
            }
            receipts.forEach { receipt ->
              Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Dimens.spacingXs)) {
                  receipt.details?.photoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.receipt_photo_preview),
                        modifier = Modifier.fillMaxWidth().height(120.dp))
                  }
                  receipt.details?.let { details ->
                    Text(DateFormat.getDateInstance().format(Date(details.scannedAtMillis)))
                  }
                  Text(
                      stringResource(
                          when (receipt.phase) {
                            InventorySavePhase.READY -> R.string.receipt_status_draft
                            InventorySavePhase.CONFIRMED -> R.string.receipt_status_saved
                            InventorySavePhase.FAILED -> R.string.inventory_save_failed
                            else -> R.string.inventory_save_pending
                          }))
                  receipt.details?.savedAtMillis?.let {
                    Text(DateFormat.getDateInstance().format(Date(it)))
                  }
                  OutlinedButton(onClick = { nav.navigate("receipt/${receipt.submission.id}") }) {
                    Text(
                        stringResource(
                            if (receipt.submission.draft) R.string.receipt_review_items
                            else R.string.receipt_view_receipt))
                  }
                }
              }
            }
            if (showBalances)
                Button(
                    onClick = onOpenBalances, modifier = Modifier.testTag(BalancesTestTags.ENTRY)) {
                      Text(stringResource(R.string.balances_title))
                    }
          }
      BarcodePhotoFlow(
          photo,
          receipt = true,
          confirmationError = stringResource(R.string.receipt_unreadable),
          onImageConfirmed = { uri ->
            scan.read(uri) { items -> newReceiptId = history.addDraft(uri, items) }
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
            history.editDraft(receipt.submission.id, stagingState.items)
          }
        }
        val leaveReview = {
          if (receipt.submission.draft) {
            history.viewModelScope.launch {
              if (history.editDraft(receipt.submission.id, staging.state.value.items)) {
                nav.popBackStack()
              }
            }
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
                    modifier = Modifier.fillMaxWidth().height(160.dp))
              }
              Text(
                  stringResource(
                      when (receipt.phase) {
                        InventorySavePhase.READY -> R.string.receipt_status_draft
                        InventorySavePhase.CONFIRMED -> R.string.receipt_status_saved
                        InventorySavePhase.FAILED -> R.string.inventory_save_failed
                        else -> R.string.inventory_save_pending
                      }))
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
) {
  val nav = rememberNavController()
  val activity = LocalContext.current.findActivity()
  DisposableEffect(photo) {
    onDispose { if (activity?.isChangingConfigurations != true) photo.discard() }
  }
  val scan: ReceiptScanViewModel =
      viewModel(
          factory =
              viewModelFactory {
                initializer { ReceiptScanViewModel(reader, createSavedStateHandle()) }
              })

  val reviewed by scan.reviewed.collectAsState()
  val pending by scan.needsReview.collectAsState()
  val saveState = save?.state?.collectAsStateWithLifecycle()?.value
  LaunchedEffect(saveState?.submission?.id) {
    saveState?.submission?.let { scan.confirm(it.items) }
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
            if (reviewed) {
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
            InventorySaveFeedback(saveState, sharedHousehold, onRetry = { save?.retry() })
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
      val recovering =
          saveState?.let {
            it.phase == InventorySavePhase.LOADING ||
                (it.phase == InventorySavePhase.RECOVERY_FAILED && it.submission == null)
          } == true
      if (recovering) {
        // SavedStateHandle may contain an older receipt than the durable journal. Do not create
        // the editable review ViewModel until its authoritative payload has been recovered.
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          HouseholdTopBar(
              title = stringResource(R.string.staging_title),
              onBack = { nav.popBackStack() },
              backTestTag = StagingTestTags.BACK)
          InventorySaveFeedback(saveState, sharedHousehold, onRetry = { save?.retry() })
        }
        return@composable
      }
      val staging: StagingViewModel =
          viewModel(
              viewModelStoreOwner = entry,
              key = "$REVIEW_MODEL_KEY:${saveState?.submission?.id ?: UNSUBMITTED_REVIEW}",
              factory =
                  viewModelFactory {
                    initializer {
                      StagingViewModel(saveState?.submission?.items ?: scan.currentItems())
                    }
                  })
      StagingScreen(
          staging,
          onConfirm = { confirmed ->
            if (save == null || save.confirm(confirmed)) {
              scan.confirm(confirmed)
              nav.popBackStack()
            }
          },
          onBack = { nav.popBackStack() },
          actionsEnabled = sharedHousehold && saveState?.canConfirm != false,
          statusContent = {
            InventorySaveFeedback(saveState, sharedHousehold, onRetry = { save?.retry() })
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
    onRetry: () -> Unit
) {
  val message =
      when {
        !sharedHousehold -> R.string.inventory_save_shared_only
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
      !state.invalidInput || state?.phase == InventorySavePhase.RECOVERY_FAILED) {
    Button(onClick = onRetry, modifier = Modifier.testTag(InventorySaveTestTags.RETRY)) {
      Text(stringResource(R.string.household_retry))
    }
  }
}

private const val RECEIPTS_ROUTE = "receipts"
private const val REVIEW_ROUTE = "review"
private const val REVIEW_MODEL_KEY = "receipt-review"
private const val UNSUBMITTED_REVIEW = "unsubmitted"
