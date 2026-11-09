// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

object StagedItemEditTestTags {
  const val DIALOG = "staged_item_edit_dialog"
  const val NAME = "staged_item_edit_name"
  const val QUANTITY = "staged_item_edit_quantity"
  const val PRICE = "staged_item_edit_price"
  const val ERROR = "staged_item_edit_error"
  const val SAVE = "staged_item_edit_save"
  const val CANCEL = "staged_item_edit_cancel"
}

/**
 * Dialog to add or edit the name, quantity and price of a staged item. It only shows [draft] and
 * reports what the user does; the ViewModel validates the values when [onSave] is called.
 */
@Composable
fun StagedItemEditDialog(
    draft: StagedItemDraft,
    onNameChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
  AlertDialog(
      modifier = Modifier.testTag(StagedItemEditTestTags.DIALOG),
      onDismissRequest = onCancel,
      title = {
        Text(
            stringResource(
                if (draft.itemId == null) R.string.staging_add_item else R.string.fridge_edit_item))
      },
      text = {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        ) {
          OutlinedTextField(
              value = draft.name,
              onValueChange = onNameChange,
              label = { Text(stringResource(R.string.fridge_item_name)) },
              isError = draft.error == StagedItemError.NAME_REQUIRED,
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag(StagedItemEditTestTags.NAME),
          )
          OutlinedTextField(
              value = draft.quantity,
              onValueChange = onQuantityChange,
              label = { Text(stringResource(R.string.fridge_quantity)) },
              isError = draft.error == StagedItemError.INVALID_QUANTITY,
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.fillMaxWidth().testTag(StagedItemEditTestTags.QUANTITY),
          )
          OutlinedTextField(
              value = draft.price,
              onValueChange = onPriceChange,
              label = { Text(stringResource(R.string.staging_price_label)) },
              prefix = { Text(stringResource(R.string.staging_currency) + " ") },
              supportingText = { Text(stringResource(R.string.staging_price_hint)) },
              isError = draft.error == StagedItemError.INVALID_PRICE,
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier.fillMaxWidth().testTag(StagedItemEditTestTags.PRICE),
          )
          draft.error?.let { error ->
            Text(
                text =
                    stringResource(
                        when (error) {
                          StagedItemError.NAME_REQUIRED -> R.string.fridge_name_required
                          StagedItemError.INVALID_QUANTITY -> R.string.fridge_quantity_invalid
                          StagedItemError.INVALID_PRICE -> R.string.staging_price_invalid
                        }),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(StagedItemEditTestTags.ERROR),
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onSave, modifier = Modifier.testTag(StagedItemEditTestTags.SAVE)) {
          Text(stringResource(R.string.fridge_save))
        }
      },
      dismissButton = {
        TextButton(onClick = onCancel, modifier = Modifier.testTag(StagedItemEditTestTags.CANCEL)) {
          Text(stringResource(R.string.fridge_cancel))
        }
      },
  )
}
