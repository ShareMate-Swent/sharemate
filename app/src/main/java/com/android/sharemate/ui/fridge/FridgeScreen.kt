// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.item.Item
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import java.time.ZoneOffset

@Composable
fun FridgeScreen(
    modifier: Modifier = Modifier,
    uiState: FridgeUiState = FridgeUiState(),
    onAddItem: (() -> Unit)? = null,
    onDismissAddItem: () -> Unit = {},
    onNameChange: (String) -> Unit = {},
    onCategoryChange: (String) -> Unit = {},
    onExpirationDateChange: (String) -> Unit = {},
    onSaveItem: () -> Unit = {},
    onRemoveItem: ((String) -> Unit)? = null,
    onCancelRemoval: () -> Unit = {},
    onConfirmRemoval: () -> Unit = {}
) {
  Column(modifier = modifier.fillMaxSize().testTag(C.Tag.fridge_screen_container)) {
    Text(
        text = stringResource(R.string.fridge_title),
        modifier =
            Modifier.align(Alignment.CenterHorizontally)
                .padding(vertical = 24.dp)
                .testTag(C.Tag.fridge_title),
        style = MaterialTheme.typography.headlineSmall)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(
                  R.string.fridge_category_all,
                  R.string.fridge_category_fruits,
                  R.string.fridge_category_vegetables,
                  R.string.fridge_category_canned_goods)
              .forEachIndexed { index, label ->
                val isSelected = index == 0
                Surface(
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {
                          selected = isSelected
                          disabled()
                        },
                    shape = RoundedCornerShape(8.dp),
                    color =
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    border =
                        if (isSelected) null
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                      Row(
                          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                              Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            }
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                          }
                    }
              }
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically) {
          Row(
              modifier = Modifier.semantics(mergeDescendants = true) { disabled() },
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically) {
                Icon(SortIcon, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.fridge_sort),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
              }
          Spacer(Modifier.weight(1f))
          Icon(
              Icons.AutoMirrored.Filled.List,
              stringResource(R.string.fridge_view_options),
              modifier = Modifier.semantics { disabled() },
              tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    Row(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          listOf(R.string.fridge_yours, R.string.fridge_shared).forEach { label ->
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { disabled() },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                  RadioButton(
                      selected = false,
                      onClick = null,
                      enabled = false,
                      modifier = Modifier.size(18.dp),
                      colors =
                          RadioButtonDefaults.colors(
                              disabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
                  Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                }
          }
        }
    Button(
        onClick = { onAddItem?.invoke() },
        enabled = onAddItem != null && !uiState.isSaving,
        modifier =
            Modifier.align(Alignment.End)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(C.Tag.fridge_add_item)) {
          Text(stringResource(R.string.fridge_add_item))
        }
    if (uiState.loadFailed) {
      Text(
          stringResource(R.string.fridge_load_failed),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp).testTag(C.Tag.fridge_inventory_error))
    }
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
        contentAlignment = Alignment.Center) {
          if (uiState.items.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_item_list),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  items(uiState.items, key = { it.id }) { item ->
                    FridgeItem(
                        item = item,
                        removalEnabled =
                            onRemoveItem != null &&
                                !uiState.isDeleting &&
                                !uiState.isSaving &&
                                !uiState.isAddItemDialogOpen,
                        onRemove = { onRemoveItem?.invoke(item.id) })
                  }
                }
          } else if (uiState.isLoading) {
            Text(stringResource(R.string.fridge_loading))
          } else {
            Text(
                stringResource(R.string.fridge_empty),
                modifier = Modifier.testTag(C.Tag.fridge_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge)
          }
        }
  }
  if (uiState.isAddItemDialogOpen) {
    AddItemDialog(
        uiState = uiState,
        onNameChange = onNameChange,
        onCategoryChange = onCategoryChange,
        onExpirationDateChange = onExpirationDateChange,
        onSave = onSaveItem,
        onDismiss = onDismissAddItem)
  }
  uiState.pendingRemovalItem?.let { item ->
    AlertDialog(
        modifier = Modifier.testTag(C.Tag.fridge_remove_dialog),
        onDismissRequest = { if (!uiState.isDeleting) onCancelRemoval() },
        title = { Text(stringResource(R.string.fridge_remove_title)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.fridge_remove_confirmation, item.name))
            if (uiState.removalFailed) {
              Text(
                  stringResource(R.string.fridge_remove_failed),
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.testTag(C.Tag.fridge_removal_error))
            }
          }
        },
        confirmButton = {
          TextButton(
              onClick = onConfirmRemoval,
              enabled = !uiState.isDeleting,
              modifier = Modifier.testTag(C.Tag.fridge_confirm_remove)) {
                Text(
                    stringResource(
                        if (uiState.isDeleting) R.string.fridge_removing
                        else R.string.fridge_remove))
              }
        },
        dismissButton = {
          TextButton(
              onClick = onCancelRemoval,
              enabled = !uiState.isDeleting,
              modifier = Modifier.testTag(C.Tag.fridge_cancel_remove)) {
                Text(stringResource(R.string.fridge_cancel))
              }
        })
  }
}

@Composable
private fun FridgeItem(item: Item, removalEnabled: Boolean, onRemove: () -> Unit) {
  val removeDescription = stringResource(R.string.fridge_remove_description, item.name)
  Surface(
      modifier = Modifier.fillMaxWidth().testTag("${C.Tag.fridge_item_prefix}${item.id}"),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(item.name, style = MaterialTheme.typography.titleMedium)
              item.category?.let { Text(stringResource(R.string.fridge_item_category, it)) }
              item.expirationDate?.let {
                Text(
                    stringResource(
                        R.string.fridge_item_expiration,
                        it.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()))
              }
              TextButton(
                  onClick = onRemove,
                  enabled = removalEnabled,
                  modifier =
                      Modifier.testTag("${C.Tag.fridge_remove_prefix}${item.id}").semantics {
                        contentDescription = removeDescription
                      }) {
                    Text(stringResource(R.string.fridge_remove))
                  }
            }
      }
}

private fun outlineIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round)
        .build()

private val SortIcon =
    outlineIcon("Sort", "M7,4 V16 M3,8 L7,4 L11,8 M17,8 V20 M13,16 L17,20 L21,16")

@Preview(showBackground = true)
@Composable
private fun FridgeScreenPreview() {
  SampleAppTheme(dynamicColor = false) { FridgeScreen() }
}
