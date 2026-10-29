// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import kotlinx.coroutines.delay

/** Displays the fridge inventory with caller-provided state and actions. */
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
    sortOrder: FridgeSortOrder? = null,
    selectedCategory: String? = null,
    selectedOwnerId: String? = null,
    categories: List<String> = emptyList(),
    ownerIds: List<String> = emptyList(),
    onSortOrderChange: ((FridgeSortOrder) -> Unit)? = null,
    onCategoryFilterChange: ((String?) -> Unit)? = null,
    onOwnerFilterChange: ((String?) -> Unit)? = null,
    onRemoveItem: ((String) -> Unit)? = null,
    onCancelRemoval: () -> Unit = {},
    onConfirmRemoval: () -> Unit = {},
    onEditItem: ((String) -> Unit)? = null,
    onQuantityChange: (String) -> Unit = {},
    onDismissEditSyncError: (FridgeEditSyncError) -> Unit = {},
    onStatusChange: ((String, ItemStatus) -> Unit)? = null,
    onDismissStatusError: (FridgeStatusError) -> Unit = {},
    onDismissRemovalSyncError: (FridgeRemovalSyncError) -> Unit = {},
    onBarcodeImageConfirmed: (suspend (String) -> Unit)? = null,
    fridgeType: FridgeType? = null,
) {
  val activeItems = uiState.items.filter { it.status == ItemStatus.ACTIVE }
  val screenModifier =
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.surface)
          .testTag(C.Tag.fridge_screen_container)
  Column(modifier = screenModifier) {
    Text(
        text = stringResource(R.string.fridge_title),
        modifier =
            Modifier.align(Alignment.CenterHorizontally)
                .padding(vertical = FridgeLayout.headerVerticalPadding)
                .testTag(C.Tag.fridge_title),
        style = MaterialTheme.typography.headlineSmall)

    fridgeType?.let { type ->
      Text(
          text =
              stringResource(
                  R.string.fridge_type_label,
                  stringResource(
                      if (type == FridgeType.PRIVATE) R.string.fridge_type_private
                      else R.string.fridge_type_shared)),
          modifier =
              Modifier.align(Alignment.CenterHorizontally)
                  .padding(bottom = 8.dp)
                  .testTag(C.Tag.fridge_type),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    FridgeControls(
        sortOrder = sortOrder,
        selectedCategory = selectedCategory,
        selectedOwnerId = selectedOwnerId,
        categories = categories,
        ownerIds = ownerIds,
        onSortOrderChange = onSortOrderChange,
        onCategoryFilterChange = onCategoryFilterChange,
        onOwnerFilterChange = onOwnerFilterChange,
        onAddItem = onAddItem,
        isSaving = uiState.isSaving,
    )

    uiState.editSyncError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen) {
        if (!uiState.isAddItemDialogOpen) {
          delay(8_000)
          onDismissEditSyncError(error)
        }
      }
      Text(
          stringResource(R.string.fridge_edit_sync_failed, error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp).testTag(C.Tag.fridge_edit_sync_error))
    }
    if (uiState.loadFailed) {
      Text(
          stringResource(R.string.fridge_load_failed),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp).testTag(C.Tag.fridge_inventory_error))
    }
    uiState.statusError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen, uiState.pendingRemovalItem) {
        if (!uiState.isAddItemDialogOpen && uiState.pendingRemovalItem == null) {
          delay(8_000)
          onDismissStatusError(error)
        }
      }
      Text(
          stringResource(
              if (error.isSyncFailure) R.string.fridge_status_sync_failed
              else R.string.fridge_status_failed,
              error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp).testTag(C.Tag.fridge_status_error))
    }
    uiState.removalSyncError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen, uiState.pendingRemovalItem) {
        if (!uiState.isAddItemDialogOpen && uiState.pendingRemovalItem == null) {
          delay(8_000)
          onDismissRemovalSyncError(error)
        }
      }
      Text(
          stringResource(R.string.fridge_remove_sync_failed, error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp).testTag(C.Tag.fridge_removal_sync_error))
    }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f)
                .padding(
                    horizontal = FridgeLayout.horizontalPadding,
                    vertical = FridgeLayout.gridVerticalPadding),
        contentAlignment = Alignment.Center) {
          if (activeItems.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_item_list),
                horizontalArrangement = Arrangement.spacedBy(FridgeLayout.gridSpacing),
                verticalArrangement = Arrangement.spacedBy(FridgeLayout.gridSpacing)) {
                  items(activeItems, key = { it.id }) { item ->
                    FridgeItem(
                        item = item,
                        editEnabled =
                            onEditItem != null &&
                                !uiState.isSaving &&
                                !uiState.isDeleting &&
                                !uiState.isAddItemDialogOpen &&
                                uiState.pendingRemovalItem == null &&
                                item.id !in uiState.pendingDeletionIds &&
                                item.id !in uiState.pendingStatusIds &&
                                item.status == ItemStatus.ACTIVE,
                        onEdit = { onEditItem?.invoke(item.id) },
                        statusEnabled =
                            onStatusChange != null &&
                                !uiState.isSaving &&
                                !uiState.isDeleting &&
                                !uiState.isAddItemDialogOpen &&
                                uiState.pendingRemovalItem == null &&
                                item.id !in uiState.pendingDeletionIds &&
                                item.id !in uiState.pendingStatusIds,
                        onStatusChange = { onStatusChange?.invoke(item.id, it) },
                        removalEnabled =
                            onRemoveItem != null &&
                                !uiState.isDeleting &&
                                !uiState.isSaving &&
                                !uiState.isAddItemDialogOpen &&
                                uiState.pendingRemovalItem == null &&
                                item.id !in uiState.pendingDeletionIds &&
                                item.id !in uiState.pendingStatusIds,
                        onRemove = { onRemoveItem?.invoke(item.id) })
                  }
                }
          } else if (uiState.isLoading) {
            Text(stringResource(R.string.fridge_loading))
          } else {
            Text(
                if (selectedCategory != null || selectedOwnerId != null) {
                  "No items match the selected filters."
                } else {
                  stringResource(R.string.fridge_empty)
                },
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
        onQuantityChange = onQuantityChange,
        onBarcodeImageConfirmed = onBarcodeImageConfirmed,
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

/** Connects the fridge presentation to its existing inventory ViewModel. */
@Composable
fun FridgeScreen(
    viewModel: FridgeViewModel,
    modifier: Modifier = Modifier,
    fridgeType: FridgeType? = null,
) {
  val uiState by viewModel.uiState.collectAsState()
  val visibleItems by viewModel.visibleItems.collectAsState()
  val sortOrder by viewModel.sortOrder.collectAsState()
  val selectedCategory by viewModel.selectedCategory.collectAsState()
  val selectedOwnerId by viewModel.selectedOwnerId.collectAsState()
  val categories =
      remember(uiState.items) {
        uiState.items
            .mapNotNull(Item::category)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
      }
  val ownerIds =
      remember(uiState.items) {
        uiState.items.map(Item::ownerId).filter(String::isNotBlank).distinct().sorted()
      }

  FridgeScreen(
      modifier = modifier,
      uiState = uiState.copy(items = visibleItems),
      onAddItem = viewModel::openAddItemDialog,
      onDismissAddItem = viewModel::dismissAddItemDialog,
      onNameChange = viewModel::updateName,
      onCategoryChange = viewModel::updateCategory,
      onExpirationDateChange = viewModel::updateExpirationDate,
      onSaveItem = viewModel::saveItem,
      onBarcodeImageConfirmed = viewModel::confirmBarcodeImage,
      onEditItem = viewModel::openEditItemDialog,
      onQuantityChange = viewModel::updateQuantity,
      onDismissEditSyncError = viewModel::dismissEditSyncError,
      onStatusChange = viewModel::setItemStatus,
      onDismissStatusError = viewModel::dismissStatusError,
      onDismissRemovalSyncError = viewModel::dismissRemovalSyncError,
      sortOrder = sortOrder,
      selectedCategory = selectedCategory,
      selectedOwnerId = selectedOwnerId,
      categories = categories,
      ownerIds = ownerIds,
      onSortOrderChange = viewModel::setSortOrder,
      onCategoryFilterChange = viewModel::setCategoryFilter,
      onOwnerFilterChange = viewModel::setOwnerFilter,
      onRemoveItem = viewModel::requestRemoval,
      onCancelRemoval = viewModel::cancelRemoval,
      onConfirmRemoval = viewModel::confirmRemoval,
      fridgeType = fridgeType,
  )
}

@Preview(showBackground = true)
@Composable
private fun FridgeScreenPreview() {
  SampleAppTheme(dynamicColor = false) { FridgeScreen() }
}
