// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
 * @param onSaved Called when the form shown here saved its item, to open the fridge.
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
      null -> Unit
    }
  }
  if (!overOtherTab) return
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val formOpen = uiState.isAddItemDialogOpen && uiState.addButtonMethod != null
  val saved by rememberUpdatedState(onSaved)
  // Whether the form shown here was closed by the user rather than by a saved item.
  var cancelled by remember { mutableStateOf(false) }
  var wasOpen by remember { mutableStateOf(formOpen) }
  LaunchedEffect(formOpen) {
    if (formOpen) {
      wasOpen = true
    } else if (wasOpen) {
      wasOpen = false
      if (!cancelled) saved()
      cancelled = false
    }
  }
  if (formOpen) {
    AddItemDialog(
        uiState = uiState,
        onNameChange = viewModel::updateName,
        onCategoryChange = viewModel::updateCategory,
        onExpirationDateChange = viewModel::updateExpirationDate,
        onSave = viewModel::saveItem,
        onDismiss = { cancelled = viewModel.dismissAddItemDialog() },
        onQuantityChange = viewModel::updateQuantity,
        onSharingChange = viewModel::updateSharing,
        onBarcodeImageConfirmed = viewModel::confirmBarcodeImage,
        personalPhotos = viewModel.personalPhotos,
        onBarcodeScanOpened = viewModel::barcodeScanOpened)
  }
}
