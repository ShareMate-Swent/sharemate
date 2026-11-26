// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.resources.C
import com.android.sharemate.ui.components.AppCard
import com.android.sharemate.ui.components.SingleLineText
import com.android.sharemate.ui.startup.LaunchStage
import com.android.sharemate.ui.startup.LaunchStatus
import com.android.sharemate.ui.startup.ReportLaunch
import com.android.sharemate.ui.theme.Dimens
import com.android.sharemate.ui.theme.ShareMateTheme
import kotlinx.coroutines.delay

private const val SYNC_ERROR_DISPLAY_DURATION_MS = 8_000L

/**
 * Displays the fridge inventory with caller-provided state and actions.
 *
 * @param expiryStatuses how close each item, by id, is to its expiration date, already worked out;
 *   an item missing from it is shown as [ExpiryStatus.NORMAL].
 * @param headerTrailing Shown at the end of the header, such as the profile photo.
 * @param householdName Name shown below the fridge title, when available.
 * @param onBarcodeScanOpened Called once the add-item form opened the barcode photo asked by
 *   [FridgeUiState.scanBarcodeOnOpen].
 */
@Composable
fun FridgeScreen(
    uiState: FridgeUiState,
    expiryStatuses: Map<String, ExpiryStatus>,
    modifier: Modifier = Modifier,
    onAddItem: (() -> Unit)? = null,
    onDismissAddItem: () -> Unit = {},
    onNameChange: (String) -> Unit = {},
    onCategoryChange: (ItemCategory?) -> Unit = {},
    onExpirationDateChange: (String) -> Unit = {},
    onSaveItem: () -> Unit = {},
    sortOrder: FridgeSortOrder? = null,
    selectedCategory: ItemCategory? = null,
    selectedOwnerId: String? = null,
    categories: List<ItemCategory> = ItemCategory.entries,
    owners: List<OwnerOption> = emptyList(),
    onSortOrderChange: ((FridgeSortOrder) -> Unit)? = null,
    onCategoryFilterChange: ((ItemCategory?) -> Unit)? = null,
    onOwnerFilterChange: ((String?) -> Unit)? = null,
    onRemoveItem: ((String) -> Unit)? = null,
    onCancelRemoval: () -> Unit = {},
    onConfirmRemoval: () -> Unit = {},
    onEditItem: ((String) -> Unit)? = null,
    onQuantityChange: (String) -> Unit = {},
    onSharingChange: (ItemSharing) -> Unit = {},
    onDismissEditSyncError: (FridgeEditSyncError) -> Unit = {},
    onStatusChange: ((String, ItemStatus) -> Unit)? = null,
    onDismissStatusError: (FridgeStatusError) -> Unit = {},
    onDismissRemovalSyncError: (FridgeRemovalSyncError) -> Unit = {},
    onBarcodeImageConfirmed: (suspend (String) -> Unit)? = null,
    personalPhotos: PersonalPhotoController? = null,
    householdName: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    onBarcodeScanOpened: () -> Unit = {},
) {
  val activeItems = uiState.items.filter { it.status == ItemStatus.ACTIVE }
  val isShared = uiState.fridgeType == FridgeType.SHARED
  val screenModifier =
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .testTag(C.Tag.fridge_screen_container)
  Column(modifier = screenModifier) {
    FridgeHeader(uiState.fridgeType, householdName, headerTrailing)

    FridgeControls(
        sortOrder = sortOrder,
        selectedCategory = selectedCategory,
        selectedOwnerId = selectedOwnerId,
        categories = categories,
        owners = owners,
        onSortOrderChange = onSortOrderChange,
        onCategoryFilterChange = onCategoryFilterChange,
        onOwnerFilterChange = onOwnerFilterChange,
        onAddItem = onAddItem,
        isSaving = uiState.isSaving,
        hasItems = activeItems.isNotEmpty(),
        showOwnerFilter = isShared,
    )

    uiState.editSyncError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen) {
        if (!uiState.isAddItemDialogOpen) {
          delay(SYNC_ERROR_DISPLAY_DURATION_MS)
          onDismissEditSyncError(error)
        }
      }
      Text(
          stringResource(R.string.fridge_edit_sync_failed, error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier =
              Modifier.padding(horizontal = Dimens.screenHorizontalPadding)
                  .testTag(C.Tag.fridge_edit_sync_error))
    }
    val photoFailure = personalPhotos?.failure?.collectAsState()?.value
    photoFailure?.let {
      Text(
          stringResource(R.string.personal_photo_attach_failed, it),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = Dimens.screenHorizontalPadding))
    }
    if (uiState.loadFailed) {
      Text(
          stringResource(R.string.fridge_load_failed),
          color = MaterialTheme.colorScheme.error,
          modifier =
              Modifier.padding(horizontal = Dimens.screenHorizontalPadding)
                  .testTag(C.Tag.fridge_inventory_error))
    }
    uiState.statusError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen, uiState.pendingRemovalItem) {
        if (!uiState.isAddItemDialogOpen && uiState.pendingRemovalItem == null) {
          delay(SYNC_ERROR_DISPLAY_DURATION_MS)
          onDismissStatusError(error)
        }
      }
      Text(
          stringResource(
              if (error.isSyncFailure) R.string.fridge_status_sync_failed
              else R.string.fridge_status_failed,
              error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier =
              Modifier.padding(horizontal = Dimens.screenHorizontalPadding)
                  .testTag(C.Tag.fridge_status_error))
    }
    uiState.removalSyncError?.let { error ->
      LaunchedEffect(error, uiState.isAddItemDialogOpen, uiState.pendingRemovalItem) {
        if (!uiState.isAddItemDialogOpen && uiState.pendingRemovalItem == null) {
          delay(SYNC_ERROR_DISPLAY_DURATION_MS)
          onDismissRemovalSyncError(error)
        }
      }
      Text(
          stringResource(R.string.fridge_remove_sync_failed, error.itemName),
          color = MaterialTheme.colorScheme.error,
          modifier =
              Modifier.padding(horizontal = Dimens.screenHorizontalPadding)
                  .testTag(C.Tag.fridge_removal_sync_error))
    }
    Box(
        modifier =
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = FridgeLayout.horizontalPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
      when {
        activeItems.isNotEmpty() -> {
          val (useSoon, everythingElse) =
              if (sortOrder == FridgeSortOrder.EXPIRATION_DATE) {
                activeItems.partition { item ->
                  val status = expiryStatuses[item.id] ?: ExpiryStatus.NORMAL
                  status != ExpiryStatus.NORMAL
                }
              } else {
                emptyList<Item>() to activeItems
              }
          val row: @Composable (Item) -> Unit = { item ->
            val actionsEnabled =
                !uiState.isSaving &&
                    !uiState.isDeleting &&
                    !uiState.isAddItemDialogOpen &&
                    uiState.pendingRemovalItem == null &&
                    item.id !in uiState.pendingDeletionIds &&
                    item.id !in uiState.pendingStatusIds
            FridgeItem(
                item = item,
                ownership = uiState.ownershipFor(item),
                expiryStatus = expiryStatuses[item.id] ?: ExpiryStatus.NORMAL,
                personalPhotos = personalPhotos,
                showOwnership = isShared,
                editEnabled = onEditItem != null && actionsEnabled,
                onEdit = { onEditItem?.invoke(item.id) },
                statusEnabled = onStatusChange != null && actionsEnabled,
                onStatusChange = { onStatusChange?.invoke(item.id, it) },
                removalEnabled = onRemoveItem != null && actionsEnabled,
                onRemove = { onRemoveItem?.invoke(item.id) },
            )
          }
          LazyColumn(modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_item_list)) {
            fridgeSection(useSoon, R.string.fridge_use_soon, row)
            fridgeSection(
                everythingElse,
                R.string.fridge_everything_else.takeIf { useSoon.isNotEmpty() },
                row,
            )
          }
        }
        uiState.isLoading -> Text(stringResource(R.string.fridge_loading))
        !uiState.loadFailed -> {
          val canClearFilters = selectedCategory != null || (isShared && selectedOwnerId != null)
          FridgeEmptyState(canClearFilters) {
            if (selectedCategory != null) onCategoryFilterChange?.invoke(null)
            if (isShared && selectedOwnerId != null) onOwnerFilterChange?.invoke(null)
          }
        }
        else -> Unit
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
        onSharingChange = onSharingChange,
        onBarcodeImageConfirmed = onBarcodeImageConfirmed,
        personalPhotos = personalPhotos,
        onBarcodeScanOpened = onBarcodeScanOpened,
        onDismiss = onDismissAddItem)
  }
  uiState.pendingRemovalItem?.let { item ->
    AlertDialog(
        modifier = Modifier.testTag(C.Tag.fridge_remove_dialog),
        onDismissRequest = { if (!uiState.isDeleting) onCancelRemoval() },
        title = { Text(stringResource(R.string.fridge_remove_title)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
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
private fun FridgeHeader(
    fridgeType: FridgeType,
    householdName: String?,
    trailing: (@Composable () -> Unit)?,
) {
  val type =
      stringResource(
          if (fridgeType == FridgeType.PRIVATE) R.string.fridge_type_private
          else R.string.fridge_type_shared)
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(
                  horizontal = FridgeLayout.horizontalPadding,
                  vertical = FridgeLayout.headerVerticalPadding,
              ),
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
          stringResource(R.string.fridge_title),
          modifier = Modifier.testTag(C.Tag.fridge_title),
          style = MaterialTheme.typography.headlineLarge,
      )
      SingleLineText(
          text =
              if (householdName.isNullOrBlank()) stringResource(R.string.fridge_type_label, type)
              else stringResource(R.string.fridge_household_label, type, householdName),
          modifier = Modifier.testTag(C.Tag.fridge_type),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    trailing?.invoke()
  }
}

private fun LazyListScope.fridgeSection(
    items: List<Item>,
    title: Int?,
    row: @Composable (Item) -> Unit,
) {
  if (items.isEmpty()) return
  if (title != null) {
    item(key = title) {
      Text(
          stringResource(title),
          modifier = Modifier.padding(top = Dimens.spacingXs, bottom = Dimens.spacingXs),
          style = MaterialTheme.typography.titleSmall,
      )
    }
  }
  itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
    val corners = MaterialTheme.shapes.large
    val firstCorner = if (index == 0) corners.topStart else CornerSize(0)
    val lastCorner = if (index == items.lastIndex) corners.bottomStart else CornerSize(0)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape =
            corners.copy(
                topStart = firstCorner,
                topEnd = firstCorner,
                bottomStart = lastCorner,
                bottomEnd = lastCorner,
            ),
    ) {
      Column {
        row(item)
        if (index != items.lastIndex) {
          HorizontalDivider(Modifier.padding(horizontal = Dimens.spacingS))
        }
      }
    }
  }
}

@Composable
private fun FridgeEmptyState(filtered: Boolean, onClearFilters: () -> Unit) {
  AppCard {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spacingL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
    ) {
      if (filtered) Icon(Icons.Default.Search, contentDescription = null)
      Text(
          stringResource(if (filtered) R.string.fridge_empty_filtered else R.string.fridge_empty),
          modifier = Modifier.testTag(C.Tag.fridge_empty),
          style = MaterialTheme.typography.titleSmall,
      )
      if (filtered) {
        TextButton(onClick = onClearFilters) { Text(stringResource(R.string.fridge_clear_filters)) }
      }
    }
  }
}

/**
 * Connects the fridge presentation to its existing inventory ViewModel.
 *
 * @param headerTrailing Shown at the end of the header, such as the profile photo.
 * @param householdName Name shown below the fridge title, when available.
 */
@Composable
fun FridgeScreen(
    viewModel: FridgeViewModel,
    modifier: Modifier = Modifier,
    householdName: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
) {
  val screenState by viewModel.screenState.collectAsState()
  val (uiState, visibleItems, sortOrder, selectedCategory, selectedOwnerId) = screenState
  ReportLaunch(
      LaunchStage.DESTINATION,
      when {
        uiState.loadFailed -> LaunchStatus.ERROR
        uiState.isLoading -> LaunchStatus.WAITING
        visibleItems.map { it.id }.toSet() !=
            uiState.items.filter { it.status == ItemStatus.ACTIVE }.map { it.id }.toSet() ->
            LaunchStatus.WAITING
        else -> LaunchStatus.READY
      })
  val owners by viewModel.ownerOptions.collectAsState()
  val expiryStatuses by viewModel.expiryStatuses.collectAsStateWithLifecycle()

  FridgeScreen(
      modifier = modifier,
      uiState = uiState.copy(items = visibleItems),
      expiryStatuses = expiryStatuses,
      onAddItem = viewModel::openAddItemDialog,
      onDismissAddItem = viewModel::dismissAddItemDialog,
      onNameChange = viewModel::updateName,
      onCategoryChange = viewModel::updateCategory,
      onExpirationDateChange = viewModel::updateExpirationDate,
      onSaveItem = viewModel::saveItem,
      onBarcodeImageConfirmed = viewModel::confirmBarcodeImage,
      onEditItem = viewModel::openEditItemDialog,
      onQuantityChange = viewModel::updateQuantity,
      onSharingChange = viewModel::updateSharing,
      onDismissEditSyncError = viewModel::dismissEditSyncError,
      onStatusChange = viewModel::setItemStatus,
      onDismissStatusError = viewModel::dismissStatusError,
      onDismissRemovalSyncError = viewModel::dismissRemovalSyncError,
      sortOrder = sortOrder,
      selectedCategory = selectedCategory,
      selectedOwnerId = selectedOwnerId,
      owners = owners,
      onSortOrderChange = viewModel::setSortOrder,
      onCategoryFilterChange = viewModel::setCategoryFilter,
      onOwnerFilterChange = viewModel::setOwnerFilter,
      onRemoveItem = viewModel::requestRemoval,
      onCancelRemoval = viewModel::cancelRemoval,
      onConfirmRemoval = viewModel::confirmRemoval,
      personalPhotos = viewModel.personalPhotos,
      headerTrailing = headerTrailing,
      householdName = householdName,
      onBarcodeScanOpened = viewModel::barcodeScanOpened,
  )
}

@Preview(showBackground = true)
@Composable
private fun FridgeScreenPreview() {
  ShareMateTheme {
    FridgeScreen(
        uiState = FridgeUiState(fridgeType = FridgeType.SHARED),
        expiryStatuses = emptyMap(),
    )
  }
}
