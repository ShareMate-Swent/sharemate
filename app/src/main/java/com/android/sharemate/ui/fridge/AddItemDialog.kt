// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity

@Composable
fun AddItemDialog(
    uiState: FridgeUiState,
    onNameChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onExpirationDateChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onQuantityChange: (String) -> Unit = {},
    onSharingChange: (ItemSharing) -> Unit = {},
    onBarcodeImageConfirmed: (suspend (String) -> Unit)? = null,
    personalPhotos: PersonalPhotoController? = null,
) {
  val context = LocalContext.current.applicationContext
  val photoViewModel: BarcodePhotoViewModel =
      viewModel(
          key = "add-item-barcode-photo",
          factory =
              viewModelFactory {
                initializer {
                  BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
                }
              })
  val photo by photoViewModel.state.collectAsState()
  val lookupError =
      uiState.barcodeLookupError?.let {
        stringResource(
            when (it) {
              BarcodeLookupError.UNREADABLE -> R.string.barcode_lookup_unreadable
              BarcodeLookupError.SCANNER_FAILED -> R.string.barcode_lookup_scanner_failed
              BarcodeLookupError.NOT_FOUND -> R.string.barcode_lookup_not_found
              BarcodeLookupError.LOOKUP_FAILED -> R.string.barcode_lookup_failed
              BarcodeLookupError.NO_DETAILS -> R.string.barcode_lookup_no_details
            })
      }
  val personalDraft = personalPhotos?.draft?.collectAsState()?.value
  val inputEnabled = !uiState.isSaving && !uiState.isScanningBarcode && !photo.busy
  val activity = LocalContext.current.findActivity()
  DisposableEffect(photoViewModel) {
    onDispose { if (activity?.isChangingConfigurations != true) photoViewModel.discard() }
  }
  AlertDialog(
      modifier = Modifier.testTag(C.Tag.fridge_add_dialog),
      onDismissRequest = {
        if (inputEnabled) {
          photoViewModel.reset()
          onDismiss()
        }
      },
      title = {
        Text(
            stringResource(
                if (uiState.editingItemId != null) R.string.fridge_edit_item
                else R.string.fridge_add_item))
      },
      text = {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
              if (uiState.editingItemId == null) {
                FilledTonalButton(
                    onClick = photoViewModel::open,
                    enabled = inputEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("barcode_photo_open")) {
                      Text(stringResource(R.string.barcode_photo_scanner))
                    }
                if (photo.confirmed && !photo.failed)
                    Text(stringResource(R.string.barcode_photo_selected))
                Text(stringResource(R.string.barcode_photo_manual))
                if (personalPhotos != null && personalDraft != null) {
                  PersonalPhotoPicker(personalDraft, personalPhotos, inputEnabled)
                }
                lookupError?.let {
                  Text(
                      it,
                      color = MaterialTheme.colorScheme.error,
                      modifier = Modifier.testTag("barcode_lookup_error"))
                }
              }
              OutlinedTextField(
                  value = uiState.itemName,
                  onValueChange = onNameChange,
                  label = { Text(stringResource(R.string.fridge_item_name)) },
                  isError = uiState.formError == FridgeFormError.NAME_REQUIRED,
                  enabled = inputEnabled,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_name_input))
              OutlinedTextField(
                  value = uiState.itemQuantity,
                  onValueChange = onQuantityChange,
                  label = { Text(stringResource(R.string.fridge_quantity)) },
                  isError = uiState.formError == FridgeFormError.INVALID_QUANTITY,
                  enabled = inputEnabled,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_quantity_input))
              OutlinedTextField(
                  value = uiState.itemCategory,
                  onValueChange = onCategoryChange,
                  label = { Text(stringResource(R.string.fridge_item_category_optional)) },
                  enabled = inputEnabled,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_category_input))
              OutlinedTextField(
                  value = uiState.expirationDateInput,
                  onValueChange = onExpirationDateChange,
                  label = { Text(stringResource(R.string.fridge_item_expiration_optional)) },
                  supportingText = { Text(stringResource(R.string.fridge_date_format)) },
                  isError = uiState.formError == FridgeFormError.INVALID_EXPIRATION_DATE,
                  enabled = inputEnabled,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_expiration_input))
              if (uiState.canShareItems && uiState.editingItemId == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                      Text(stringResource(R.string.fridge_item_share_with_household))
                      Switch(
                          checked = uiState.itemSharing == ItemSharing.SHARED,
                          onCheckedChange = {
                            onSharingChange(if (it) ItemSharing.SHARED else ItemSharing.PERSONAL)
                          },
                          enabled = inputEnabled,
                          modifier = Modifier.testTag(C.Tag.fridge_share_switch))
                    }
              }
              uiState.formError?.let { error ->
                Text(
                    text =
                        stringResource(
                            when (error) {
                              FridgeFormError.INVALID_QUANTITY -> R.string.fridge_quantity_invalid
                              FridgeFormError.NAME_REQUIRED -> R.string.fridge_name_required
                              FridgeFormError.INVALID_EXPIRATION_DATE ->
                                  R.string.fridge_date_invalid
                              FridgeFormError.SAVE_FAILED -> R.string.fridge_save_failed
                            }),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(C.Tag.fridge_form_error))
              }
            }
      },
      confirmButton = {
        TextButton(
            onClick = onSave,
            enabled = inputEnabled && personalDraft?.importing != true,
            modifier = Modifier.testTag(C.Tag.fridge_save_item)) {
              Text(
                  stringResource(
                      if (uiState.isSaving) R.string.fridge_saving else R.string.fridge_save))
            }
      },
      dismissButton = {
        TextButton(
            onClick = {
              photoViewModel.reset()
              onDismiss()
            },
            enabled = inputEnabled,
            modifier = Modifier.testTag(C.Tag.fridge_cancel_item)) {
              Text(stringResource(R.string.fridge_cancel))
            }
      })
  BarcodePhotoFlow(
      photoViewModel, confirmationError = lookupError, onImageConfirmed = onBarcodeImageConfirmed)
}
