// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.model.item.ItemQuantity
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.resources.C
import com.android.sharemate.ui.TextLengthCounter
import com.android.sharemate.ui.barcode.BarcodeFormTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoPicker
import com.android.sharemate.ui.navigation.AddItemMethod
import com.android.sharemate.ui.photo.DiscardPhotoOnDispose
import com.android.sharemate.ui.photo.OnPhotoPickerCancelled
import com.android.sharemate.ui.photo.photoPickerViewModel
import com.android.sharemate.ui.theme.Dimens

/**
 * The form to add an item, or to edit one when [FridgeUiState.editingItemId] is set.
 *
 * @param onBarcodeScanOpened Called once the form opened the barcode photo asked by
 *   [FridgeUiState.scanBarcodeOnOpen].
 */
@Composable
fun AddItemDialog(
    uiState: FridgeUiState,
    onNameChange: (String) -> Unit,
    onCategoryChange: (ItemCategory?) -> Unit,
    onExpirationDateChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onQuantityChange: (String) -> Unit = {},
    onSharingChange: (ItemSharing) -> Unit = {},
    onBarcodeImageConfirmed: (suspend (String) -> Unit)? = null,
    personalPhotos: PersonalPhotoController? = null,
    onBarcodeScanOpened: () -> Unit = {},
) {
  val photoViewModel = photoPickerViewModel("add-item-barcode-photo")
  LaunchedEffect(uiState.scanBarcodeOnOpen) {
    if (uiState.scanBarcodeOnOpen) {
      photoViewModel.open()
      onBarcodeScanOpened()
    }
  }
  OnPhotoPickerCancelled(photoViewModel) {
    // Opened only to scan a barcode: leaving the photo leaves the form too.
    if (uiState.closeOnBarcodeCancel) {
      photoViewModel.reset()
      onDismiss()
    }
  }
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
  DiscardPhotoOnDispose(photoViewModel)
  BarcodePhotoPicker(
      photoViewModel, confirmationError = lookupError, onImageConfirmed = onBarcodeImageConfirmed)
  // Opened only to scan a barcode: the camera shows alone until a photo is read, so the form does
  // not flash before it, open over it after a failed lookup, or show while it closes.
  if (uiState.addButtonMethod == AddItemMethod.BARCODE &&
      (uiState.scanBarcodeOnOpen || uiState.closeOnBarcodeCancel || photo.visible))
      return
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
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
              if (uiState.editingItemId == null) {
                FilledTonalButton(
                    onClick = photoViewModel::open,
                    enabled = inputEnabled,
                    modifier = Modifier.fillMaxWidth().testTag(BarcodeFormTestTags.OPEN)) {
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
                      modifier = Modifier.testTag(BarcodeFormTestTags.LOOKUP_ERROR))
                }
              }
              OutlinedTextField(
                  value = uiState.itemName,
                  onValueChange = onNameChange,
                  label = { Text(stringResource(R.string.fridge_item_name)) },
                  supportingText = { TextLengthCounter(uiState.itemName, TextLimits.ITEM_NAME) },
                  isError =
                      uiState.formError == FridgeFormError.NAME_REQUIRED ||
                          uiState.formError == FridgeFormError.NAME_TOO_LONG,
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
              CategoryPicker(
                  selected = uiState.itemCategory,
                  onSelect = onCategoryChange,
                  enabled = inputEnabled,
                  modifier = Modifier.fillMaxWidth())
              OutlinedTextField(
                  value = uiState.expirationDateInput,
                  onValueChange = onExpirationDateChange,
                  label = { Text(stringResource(R.string.fridge_item_expiration_optional)) },
                  supportingText = { Text(stringResource(R.string.fridge_date_format)) },
                  isError = uiState.formError == FridgeFormError.INVALID_EXPIRATION_DATE,
                  enabled = inputEnabled,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_expiration_input))
              if (uiState.fridgeType == FridgeType.SHARED && uiState.editingItemId == null) {
                // The whole row is the toggle, so assistive technologies announce the label with
                // it.
                val shared = uiState.itemSharing == ItemSharing.SHARED
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier =
                        Modifier.fillMaxWidth()
                            .testTag(C.Tag.fridge_share_switch)
                            .toggleable(
                                value = shared,
                                enabled = inputEnabled,
                                role = Role.Switch,
                                onValueChange = {
                                  onSharingChange(
                                      if (it) ItemSharing.SHARED else ItemSharing.PERSONAL)
                                })) {
                      Text(stringResource(R.string.fridge_item_share_with_household))
                      Switch(checked = shared, onCheckedChange = null, enabled = inputEnabled)
                    }
              } else if (uiState.showSharingSelector && uiState.editingItemId != null) {
                Column(Modifier.selectableGroup().testTag(C.Tag.fridge_sharing_selector)) {
                  Text(stringResource(R.string.fridge_item_sharing))
                  for (sharing in ItemSharing.values()) {
                    val selected = uiState.itemSharing == sharing
                    val enabled = uiState.canChangeSharing && inputEnabled
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onSharingChange(sharing) })
                                .testTag(
                                    if (sharing == ItemSharing.SHARED) C.Tag.fridge_sharing_shared
                                    else C.Tag.fridge_sharing_personal)
                                .padding(vertical = Dimens.spacingXxs),
                        verticalAlignment = Alignment.CenterVertically) {
                          RadioButton(selected = selected, onClick = null, enabled = enabled)
                          Text(
                              stringResource(
                                  if (sharing == ItemSharing.SHARED) R.string.fridge_item_shared
                                  else R.string.fridge_item_personal))
                        }
                  }
                  if (!uiState.canChangeSharing) {
                    Text(
                        stringResource(R.string.fridge_sharing_owner_only),
                        style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
              uiState.formError?.let { error ->
                Text(
                    text = fridgeFormErrorText(error),
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
}

/** The message of [error], including the limit for the errors that name one. */
@Composable
private fun fridgeFormErrorText(error: FridgeFormError): String =
    when (error) {
      FridgeFormError.INVALID_QUANTITY ->
          stringResource(R.string.fridge_quantity_invalid, ItemQuantity.MIN, ItemQuantity.MAX)
      FridgeFormError.NAME_REQUIRED -> stringResource(R.string.fridge_name_required)
      FridgeFormError.NAME_TOO_LONG ->
          stringResource(R.string.fridge_name_too_long, TextLimits.ITEM_NAME)
      FridgeFormError.INVALID_EXPIRATION_DATE -> stringResource(R.string.fridge_date_invalid)
      FridgeFormError.SAVE_FAILED -> stringResource(R.string.fridge_save_failed)
    }
