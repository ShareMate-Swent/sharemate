// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.receipt.ReceiptPhotoReader
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.staging.StagingScreen
import com.android.sharemate.ui.staging.StagingViewModel
import com.android.sharemate.ui.theme.Dimens

@Composable
fun ReceiptsScreen() {
  val context = LocalContext.current.applicationContext
  val reader = remember(context) { ReceiptPhotoReader(context) }
  val photo: BarcodePhotoViewModel =
      viewModel(
          key = "receipt-photo",
          factory =
              viewModelFactory {
                initializer {
                  BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
                }
              })
  ReceiptScanFlow(photo, reader)
}

@Composable
internal fun ReceiptScanFlow(photo: BarcodePhotoViewModel, reader: ReceiptReader) {
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
  LaunchedEffect(pending) {
    if (pending) {
      nav.navigate(REVIEW_ROUTE) { launchSingleTop = true }
      scan.reviewOpened()
    }
  }
  NavHost(nav, startDestination = RECEIPTS_ROUTE) {
    composable(RECEIPTS_ROUTE) {
      Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        Text(
            stringResource(R.string.receipts_title),
            modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = {
              photo.reset()
              photo.open()
            }) {
              Text(stringResource(R.string.scan_receipt))
            }
        if (reviewed) {
          Text(stringResource(R.string.receipt_reviewed))
          Button(onClick = { nav.navigate(REVIEW_ROUTE) }) {
            Text(stringResource(R.string.staging_title))
          }
        }
      }
      BarcodePhotoFlow(
          photo,
          receipt = true,
          confirmationError = stringResource(R.string.receipt_unreadable),
          onImageConfirmed = scan::read)
    }
    composable(REVIEW_ROUTE) { entry ->
      val staging: StagingViewModel =
          viewModel(
              viewModelStoreOwner = entry,
              factory = viewModelFactory { initializer { StagingViewModel(scan.currentItems()) } })
      StagingScreen(
          staging,
          onConfirm = { confirmed ->
            scan.confirm(confirmed)
            nav.popBackStack()
          },
          onBack = { nav.popBackStack() })
    }
  }
}

private const val RECEIPTS_ROUTE = "receipts"
private const val REVIEW_ROUTE = "review"
