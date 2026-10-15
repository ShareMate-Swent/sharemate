// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.android.sharemate.R
import com.android.sharemate.resources.C

/** Displays inventory filters and separate, disabled shared/personal presentation controls. */
@Composable
internal fun FridgeControls(
    sortOrder: FridgeSortOrder?,
    selectedCategory: String?,
    selectedOwnerId: String?,
    categories: List<String>,
    ownerIds: List<String>,
    onSortOrderChange: ((FridgeSortOrder) -> Unit)?,
    onCategoryFilterChange: ((String?) -> Unit)?,
    onOwnerFilterChange: ((String?) -> Unit)?,
    onAddItem: (() -> Unit)?,
    isSaving: Boolean,
) {
  val hasFunctionalFilters =
      sortOrder != null &&
          onSortOrderChange != null &&
          onCategoryFilterChange != null &&
          onOwnerFilterChange != null
  val availableCategories = (categories + listOfNotNull(selectedCategory)).distinct()
  val categoryEnabled = hasFunctionalFilters && availableCategories.isNotEmpty()

  Row(
      modifier =
          Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = FridgeLayout.categoryHorizontalPadding),
      horizontalArrangement = Arrangement.spacedBy(FridgeLayout.chipSpacing)) {
        CategoryChip(
            label = stringResource(R.string.fridge_category_all),
            selected = selectedCategory == null,
            enabled = categoryEnabled,
            tag = "fridge_filter_category_all",
            showCheckmark = selectedCategory == null,
            onClick = { onCategoryFilterChange?.invoke(null) })
        availableCategories.forEach { category ->
          CategoryChip(
              label = category,
              selected = selectedCategory == category,
              enabled = hasFunctionalFilters,
              tag = "fridge_filter_category_$category",
              onClick = { onCategoryFilterChange?.invoke(category) })
        }
      }

  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(
                  horizontal = FridgeLayout.horizontalPadding,
                  vertical = FridgeLayout.controlsVerticalPadding),
      horizontalArrangement = Arrangement.spacedBy(FridgeLayout.controlSpacing),
      verticalAlignment = Alignment.CenterVertically) {
        SortControl(sortOrder, hasFunctionalFilters, onSortOrderChange)
        Box(Modifier.weight(1f)) {
          OwnerControl(selectedOwnerId, ownerIds, hasFunctionalFilters, onOwnerFilterChange)
        }
        TextButton(
            onClick = { onAddItem?.invoke() },
            enabled = onAddItem != null && !isSaving,
            modifier = Modifier.testTag(C.Tag.fridge_add_item)) {
              Text(
                  stringResource(R.string.fridge_add_item),
                  style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal))
            }
        IconButton(onClick = {}, enabled = false) {
          Icon(
              Icons.AutoMirrored.Filled.List,
              stringResource(R.string.fridge_view_options),
              tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

  OwnershipControls()
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    showCheckmark: Boolean = false,
    onClick: () -> Unit,
) {
  FilterChip(
      selected = selected,
      enabled = enabled,
      onClick = onClick,
      label = {
        Text(
            label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal))
      },
      modifier = Modifier.testTag(tag),
      shape = RoundedCornerShape(FridgeLayout.chipCornerRadius),
      border =
          if (selected) null
          else BorderStroke(FridgeLayout.chipBorderWidth, MaterialTheme.colorScheme.outlineVariant),
      colors =
          FilterChipDefaults.filterChipColors(
              containerColor = MaterialTheme.colorScheme.surface,
              labelColor = MaterialTheme.colorScheme.onSurface,
              selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
              selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
              disabledContainerColor = MaterialTheme.colorScheme.surface,
              disabledSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              disabledLabelColor = MaterialTheme.colorScheme.onSurface,
              disabledLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer),
      leadingIcon =
          if (showCheckmark) {
            { Icon(Icons.Default.Check, null, modifier = Modifier.size(FridgeLayout.iconSize)) }
          } else null)
}

@Composable
private fun SortControl(
    sortOrder: FridgeSortOrder?,
    enabled: Boolean,
    onSelected: ((FridgeSortOrder) -> Unit)?,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { expanded = true }, enabled = enabled) {
      Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(FridgeLayout.iconSize))
      Text(
          stringResource(R.string.fridge_sort),
          modifier = Modifier.padding(start = FridgeLayout.controlSpacing),
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      listOf(
              FridgeSortOrder.EXPIRATION_DATE to R.string.fridge_sort_expiration_date,
              FridgeSortOrder.NAME to R.string.fridge_sort_name)
          .forEach { (order, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                modifier = Modifier.semantics { selected = sortOrder == order },
                onClick = {
                  onSelected?.invoke(order)
                  expanded = false
                })
          }
    }
  }
}

@Composable
private fun OwnerControl(
    selectedOwnerId: String?,
    ownerIds: List<String>,
    enabled: Boolean,
    onSelected: ((String?) -> Unit)?,
) {
  var expanded by remember { mutableStateOf(false) }
  val label =
      if (selectedOwnerId == null) stringResource(R.string.fridge_owner_filter)
      else stringResource(R.string.fridge_owner_filter_selected, selectedOwnerId)
  Box {
    TextButton(
        onClick = { expanded = true },
        enabled = enabled && (ownerIds.isNotEmpty() || selectedOwnerId != null),
        modifier =
            Modifier.semantics {
              selected = selectedOwnerId != null
              contentDescription = label
            }) {
          Text(
              label,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal))
        }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.fridge_owner_filter_all)) },
          modifier = Modifier.testTag("fridge_filter_owner_all"),
          onClick = {
            onSelected?.invoke(null)
            expanded = false
          })
      ownerIds.forEach { ownerId ->
        DropdownMenuItem(
            text = { Text(ownerId) },
            modifier = Modifier.testTag("fridge_filter_owner_$ownerId"),
            onClick = {
              onSelected?.invoke(ownerId)
              expanded = false
            })
      }
    }
  }
}

@Composable
private fun OwnershipControls() {
  Row(
      modifier = Modifier.padding(horizontal = FridgeLayout.horizontalPadding),
      horizontalArrangement = Arrangement.spacedBy(FridgeLayout.ownershipSpacing)) {
        listOf(R.string.fridge_yours, R.string.fridge_shared).forEach { label ->
          Row(
              modifier =
                  Modifier.semantics(mergeDescendants = true) {
                    disabled()
                    selected = false
                    role = Role.RadioButton
                  },
              horizontalArrangement = Arrangement.spacedBy(FridgeLayout.ownershipLabelSpacing),
              verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    null,
                    modifier = Modifier.size(FridgeLayout.ownershipIconSize),
                    tint =
                        if (label == R.string.fridge_yours) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline)
                Text(
                    stringResource(label),
                    style =
                        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal))
              }
        }
      }
}
