// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

/** Displays existing fridge filters, or disabled controls when callbacks are unavailable. */
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
) {
  val hasFunctionalFilters =
      sortOrder != null &&
          onSortOrderChange != null &&
          onCategoryFilterChange != null &&
          onOwnerFilterChange != null

  if (hasFunctionalFilters) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
          selected = sortOrder == FridgeSortOrder.EXPIRATION_DATE,
          onClick = { onSortOrderChange?.invoke(FridgeSortOrder.EXPIRATION_DATE) },
          label = { Text("Expiry date") },
      )
      FilterChip(
          selected = sortOrder == FridgeSortOrder.NAME,
          onClick = { onSortOrderChange?.invoke(FridgeSortOrder.NAME) },
          label = { Text("Name") },
      )
      ItemFilterChip(
          label = "Category",
          selectedValue = selectedCategory,
          options = categories,
          onSelected = { onCategoryFilterChange?.invoke(it) },
      )
      ItemFilterChip(
          label = "Owner",
          selectedValue = selectedOwnerId,
          options = ownerIds,
          onSelected = { onOwnerFilterChange?.invoke(it) },
      )
    }
  } else {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
  }
}

@Composable
private fun ItemFilterChip(
    label: String,
    selectedValue: String?,
    options: List<String>,
    onSelected: (String?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val chipLabel = selectedValue?.let { "$label: $it" } ?: label

  Box {
    FilterChip(
        selected = selectedValue != null,
        enabled = options.isNotEmpty() || selectedValue != null,
        onClick = { expanded = true },
        label = { Text(chipLabel) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text(if (label == "Category") "All categories" else "All owners") },
          modifier = Modifier.testTag("fridge_filter_${label.lowercase()}_all"),
          onClick = {
            onSelected(null)
            expanded = false
          },
      )
      options.forEach { option ->
        DropdownMenuItem(
            text = { Text(option) },
            modifier = Modifier.testTag("fridge_filter_${label.lowercase()}_$option"),
            onClick = {
              onSelected(option)
              expanded = false
            },
        )
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
