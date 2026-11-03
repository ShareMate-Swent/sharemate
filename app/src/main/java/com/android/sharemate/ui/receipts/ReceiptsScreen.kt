// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptPhotoReader
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.staging.StagingScreen
import com.android.sharemate.ui.staging.StagingViewModel

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
  ReceiptScanFlow(photo) { uri -> reader.read(Uri.parse(uri)) }
}

@Composable
internal fun ReceiptScanFlow(
    photo: BarcodePhotoViewModel,
    read: suspend (String) -> List<ReceiptItem>
) {
  val nav = rememberNavController()
  val activity = LocalContext.current.findActivity()
  DisposableEffect(photo) {
    onDispose { if (activity?.isChangingConfigurations != true) photo.discard() }
  }
  var items by
      rememberSaveable(stateSaver = receiptItemsSaver) { mutableStateOf(emptyList<StagedItem>()) }
  var reviewed by rememberSaveable { mutableStateOf(false) }
  NavHost(nav, startDestination = "receipts") {
    composable("receipts") {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Receipts",
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
          Button(onClick = { nav.navigate("review") }) {
            Text(stringResource(R.string.staging_title))
          }
        }
      }
      BarcodePhotoFlow(
          photo, receipt = true, confirmationError = stringResource(R.string.receipt_unreadable)) {
              uri ->
            val products = read(uri)
            require(products.isNotEmpty())
            items =
                products.mapIndexed { index, product ->
                  StagedItem(index.toString(), product.name, priceCents = product.priceCents)
                }
            reviewed = false
            nav.navigate("review") { launchSingleTop = true }
          }
    }
    composable("review") { entry ->
      val staging: StagingViewModel =
          viewModel(
              viewModelStoreOwner = entry,
              factory = viewModelFactory { initializer { StagingViewModel(items) } })
      StagingScreen(
          staging,
          onConfirm = { confirmed ->
            items = confirmed
            reviewed = true
            nav.popBackStack()
          },
          onBack = { nav.popBackStack() })
    }
  }
}

private val receiptItemsSaver =
    listSaver<List<StagedItem>, Bundle>(
        save = { items ->
          items.map {
            bundleOf(
                "id" to it.id,
                "name" to it.name,
                "quantity" to it.quantity,
                "price" to (it.priceCents ?: -1L))
          }
        },
        restore = { list ->
          list.map {
            StagedItem(
                it.getString("id")!!,
                it.getString("name")!!,
                it.getInt("quantity"),
                it.getLong("price").takeIf { cents -> cents >= 0 })
          }
        })
