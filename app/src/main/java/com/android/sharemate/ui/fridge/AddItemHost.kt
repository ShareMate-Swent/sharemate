// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.ui.navigation.AddItemMethod

/**
 * Runs the fridge's ways to add an item that the add button of the bottom bar offers, over the open
 * tab: the form opens where the user is, and only a saved item leads to the fridge.
 *
 * It starts [request] when it is one of the fridge's ways ([AddItemMethod.BARCODE] or
 * [AddItemMethod.MANUAL]). While [overOtherTab], it also shows the form, which the fridge screen
 * shows otherwise.
 *
 * @param viewModel The fridge's ViewModel, the same one as the fridge screen's.
 * @param request The way the user chose in the sheet, if any.
 * @param onRequestHandled Called once [request] is started.
 * @param overOtherTab Whether another tab than the fridge is open, so the fridge screen is not
 *   there to show the form.
 * @param onSaved Called when an item from the add button's form was saved while another tab is
 *   open, to open the fridge.
 */
@Composable
fun AddItemHost(
    viewModel: FridgeViewModel,
    request: AddItemMethod?,
    onRequestHandled: (AddItemMethod) -> Unit,
    overOtherTab: Boolean,
    onSaved: () -> Unit,
) {
  LaunchedEffect(request) {
    when (request) {
      AddItemMethod.BARCODE,
      AddItemMethod.MANUAL -> {
        viewModel.openAddItemDialog(fromAddButton = request)
        onRequestHandled(request)
      }
      AddItemMethod.RECEIPT,
      null -> Unit
    }
  }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val openFridge by rememberUpdatedState(overOtherTab)
  val saved by rememberUpdatedState(onSaved)
  // The ViewModel keeps the saved item until it is handled here, so a save that ends while the
  // Activity is recreated still opens the fridge.
  LaunchedEffect(uiState.addButtonItemSaved) {
    if (uiState.addButtonItemSaved) {
      if (openFridge) saved()
      viewModel.addButtonSaveHandled()
    }
  }
  if (!overOtherTab) return
  val formOpen = uiState.isAddItemDialogOpen && uiState.addButtonMethod != null
  if (formOpen) {
    AddItemDialog(
        uiState = uiState,
        onNameChange = viewModel::updateName,
        onCategoryChange = viewModel::updateCategory,
        onExpirationDateChange = viewModel::updateExpirationDate,
        onSave = viewModel::saveItem,
        onDismiss = viewModel::dismissAddItemDialog,
        onQuantityChange = viewModel::updateQuantity,
        onSharingChange = viewModel::updateSharing,
        onBarcodeImageConfirmed = viewModel::confirmBarcodeImage,
        personalPhotos = viewModel.personalPhotos,
        onBarcodeScanOpened = viewModel::barcodeScanOpened)
  }
}
