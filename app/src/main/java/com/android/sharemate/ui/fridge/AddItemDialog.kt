// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.resources.C

@Composable
fun AddItemDialog(
    uiState: FridgeUiState,
    onNameChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onExpirationDateChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onQuantityChange: (String) -> Unit = {},
) {
  AlertDialog(
      modifier = Modifier.testTag(C.Tag.fridge_add_dialog),
      onDismissRequest = { if (!uiState.isSaving) onDismiss() },
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
              OutlinedTextField(
                  value = uiState.itemName,
                  onValueChange = onNameChange,
                  label = { Text(stringResource(R.string.fridge_item_name)) },
                  isError = uiState.formError == FridgeFormError.NAME_REQUIRED,
                  enabled = !uiState.isSaving,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_name_input))
              if (uiState.editingItemId != null) {
                OutlinedTextField(
                    value = uiState.itemQuantity,
                    onValueChange = onQuantityChange,
                    label = { Text(stringResource(R.string.fridge_quantity)) },
                    isError = uiState.formError == FridgeFormError.INVALID_QUANTITY,
                    enabled = !uiState.isSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fridge_quantity_input"))
              }
              OutlinedTextField(
                  value = uiState.itemCategory,
                  onValueChange = onCategoryChange,
                  label = { Text(stringResource(R.string.fridge_item_category_optional)) },
                  enabled = !uiState.isSaving,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_category_input))
              OutlinedTextField(
                  value = uiState.expirationDateInput,
                  onValueChange = onExpirationDateChange,
                  label = { Text(stringResource(R.string.fridge_item_expiration_optional)) },
                  supportingText = { Text(stringResource(R.string.fridge_date_format)) },
                  isError = uiState.formError == FridgeFormError.INVALID_EXPIRATION_DATE,
                  enabled = !uiState.isSaving,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag(C.Tag.fridge_expiration_input))
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
            enabled = !uiState.isSaving,
            modifier = Modifier.testTag(C.Tag.fridge_save_item)) {
              Text(
                  stringResource(
                      if (uiState.isSaving) R.string.fridge_saving else R.string.fridge_save))
            }
      },
      dismissButton = {
        TextButton(
            onClick = onDismiss,
            enabled = !uiState.isSaving,
            modifier = Modifier.testTag(C.Tag.fridge_cancel_item)) {
              Text(stringResource(R.string.fridge_cancel))
            }
      })
}
