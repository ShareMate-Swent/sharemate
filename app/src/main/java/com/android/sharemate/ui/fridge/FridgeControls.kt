// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.android.sharemate.R
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.resources.C
import com.android.sharemate.ui.components.AppChip
import com.android.sharemate.ui.theme.Dimens
import java.security.MessageDigest

internal fun ownerFallbackCodes(ownerIds: Collection<String>): Map<String, String> {
  val idsByCode =
      ownerIds.distinct().groupBy { id ->
        MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
      }
  return idsByCode
      .flatMap { (code, ids) ->
        ids.sorted().mapIndexed { index, id ->
          id to if (ids.size == 1) code else "$code-${index + 1}"
        }
      }
      .toMap()
}

/** Displays inventory filters and the manual add action. */
@Composable
internal fun FridgeControls(
    sortOrder: FridgeSortOrder?,
    selectedCategory: ItemCategory?,
    selectedOwnerId: String?,
    categories: List<ItemCategory>,
    owners: List<OwnerOption>,
    onSortOrderChange: ((FridgeSortOrder) -> Unit)?,
    onCategoryFilterChange: ((ItemCategory?) -> Unit)?,
    onOwnerFilterChange: ((String?) -> Unit)?,
    onAddItem: (() -> Unit)?,
    isSaving: Boolean,
    hasItems: Boolean,
    showOwnerFilter: Boolean,
) {
  // The selected chip stays while its last item goes, so the filter can still be cleared.
  val availableCategories =
      (categories + listOfNotNull(selectedCategory)).distinct().sortedBy(ItemCategory::ordinal)
  // With an empty fridge there is nothing to filter, unless a filter is still set and must clear.
  val categoryEnabled =
      onCategoryFilterChange != null &&
          availableCategories.isNotEmpty() &&
          (hasItems || selectedCategory != null)

  Row(
      modifier =
          Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = FridgeLayout.horizontalPadding),
      horizontalArrangement = Arrangement.spacedBy(FridgeLayout.chipSpacing),
  ) {
    AppChip(
        label = stringResource(R.string.fridge_category_all),
        selected = selectedCategory == null,
        enabled = categoryEnabled,
        modifier = Modifier.testTag(C.Tag.fridge_filter_category_all),
        onClick = { onCategoryFilterChange?.invoke(null) },
    )
    availableCategories.forEach { category ->
      AppChip(
          label = stringResource(category.labelRes()),
          selected = selectedCategory == category,
          enabled = categoryEnabled,
          modifier = Modifier.testTag(C.Tag.fridgeFilterCategory(category)),
          onClick = { onCategoryFilterChange?.invoke(category) },
      )
    }
  }

  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(
                  horizontal = FridgeLayout.horizontalPadding,
                  vertical = FridgeLayout.controlsVerticalPadding,
              ),
      horizontalArrangement = Arrangement.spacedBy(FridgeLayout.controlSpacing),
  ) {
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(FridgeLayout.controlSpacing),
    ) {
      SortControl(
          sortOrder,
          onSortOrderChange,
          Modifier.weight(FridgeLayout.sortFilterWeight, fill = false))
      if (showOwnerFilter) {
        OwnerControl(
            selectedOwnerId, owners, onOwnerFilterChange, Modifier.weight(1f, fill = false))
      }
    }
    TextButton(
        onClick = { onAddItem?.invoke() },
        enabled = onAddItem != null && !isSaving,
        modifier = Modifier.testTag(C.Tag.fridge_add_item),
    ) {
      Text(stringResource(R.string.fridge_add_item))
    }
  }
}

@Composable
private fun SortControl(
    sortOrder: FridgeSortOrder?,
    onSelected: ((FridgeSortOrder) -> Unit)?,
    modifier: Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    FilterButton(
        label =
            if (sortOrder == null) stringResource(R.string.fridge_sort)
            else
                stringResource(R.string.fridge_sort_selected, stringResource(sortOrder.labelRes())),
        enabled = sortOrder != null && onSelected != null,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      FridgeSortOrder.entries.forEach { order ->
        val selected = sortOrder == order
        DropdownMenuItem(
            text = { Text(stringResource(order.labelRes())) },
            modifier = Modifier.semantics { this.selected = selected },
            trailingIcon = { if (selected) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = {
              onSelected?.invoke(order)
              expanded = false
            },
        )
      }
    }
  }
}

@Composable
private fun OwnerControl(
    selectedOwnerId: String?,
    owners: List<OwnerOption>,
    onSelected: ((String?) -> Unit)?,
    modifier: Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val unknownOwner = stringResource(R.string.fridge_owner_unknown)
  val fallbackCodes =
      remember(owners, selectedOwnerId) {
        ownerFallbackCodes(owners.map { it.id } + listOfNotNull(selectedOwnerId))
      }
  val fallbackLabels =
      fallbackCodes.mapValues { (_, code) ->
        stringResource(R.string.fridge_owner_unknown_with_code, unknownOwner, code)
      }
  val label =
      if (selectedOwnerId == null) stringResource(R.string.fridge_owner_filter_all)
      else
          stringResource(
              R.string.fridge_owner_filter_selected,
              owners.firstOrNull { it.id == selectedOwnerId }?.pseudo
                  ?: fallbackLabels.getValue(selectedOwnerId),
          )
  Box(modifier) {
    FilterButton(
        label = label,
        onClick = { expanded = true },
        enabled = onSelected != null && (owners.isNotEmpty() || selectedOwnerId != null),
        modifier =
            Modifier.semantics {
              selected = selectedOwnerId != null
              contentDescription = label
            },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.fridge_owner_filter_all)) },
          modifier =
              Modifier.testTag(C.Tag.fridge_filter_owner_all).semantics {
                selected = selectedOwnerId == null
              },
          trailingIcon = {
            if (selectedOwnerId == null) Icon(Icons.Default.Check, contentDescription = null)
          },
          onClick = {
            onSelected?.invoke(null)
            expanded = false
          },
      )
      owners.forEach { owner ->
        DropdownMenuItem(
            text = { Text(owner.pseudo ?: fallbackLabels.getValue(owner.id)) },
            modifier =
                Modifier.testTag(C.Tag.fridgeFilterOwner(owner.id)).semantics {
                  selected = selectedOwnerId == owner.id
                },
            trailingIcon = {
              if (selectedOwnerId == owner.id) Icon(Icons.Default.Check, contentDescription = null)
            },
            onClick = {
              onSelected?.invoke(owner.id)
              expanded = false
            },
        )
      }
    }
  }
}

private fun FridgeSortOrder.labelRes(): Int =
    when (this) {
      FridgeSortOrder.EXPIRATION_DATE -> R.string.fridge_sort_expiration_date
      FridgeSortOrder.NAME -> R.string.fridge_sort_name
    }

@Composable
private fun FilterButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      contentPadding = PaddingValues(horizontal = Dimens.spacingXs, vertical = Dimens.spacingXs),
  ) {
    Text(
        label,
        modifier = Modifier.weight(1f, fill = false),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Icon(
        Icons.Default.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.size(FridgeLayout.iconSize),
    )
  }
}
