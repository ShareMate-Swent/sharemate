// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.resources.C

/** The translated name of the category. */
@StringRes
internal fun ItemCategory.labelRes(): Int =
    when (this) {
      ItemCategory.FRUITS -> R.string.fridge_category_fruits
      ItemCategory.VEGETABLES -> R.string.fridge_category_vegetables
      ItemCategory.DAIRY -> R.string.fridge_category_dairy
      ItemCategory.MEAT_FISH -> R.string.fridge_category_meat_fish
      ItemCategory.DRINKS -> R.string.fridge_category_drinks
      ItemCategory.PANTRY -> R.string.fridge_category_pantry
      ItemCategory.FROZEN -> R.string.fridge_category_frozen
      ItemCategory.OTHER -> R.string.fridge_category_other
    }

/** The translated name of the category, or "No category" for null. */
@StringRes
internal fun ItemCategory?.labelOrNoneRes(): Int = this?.labelRes() ?: R.string.fridge_category_none

/**
 * The optional category field of the item form: it shows the picked category and opens the fixed
 * list, starting with "No category".
 *
 * @param selected The picked category; null is "No category".
 * @param onSelect Called with the category the user picked, or null for "No category".
 * @param enabled Whether the field can be opened.
 * @param modifier Applied to the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPicker(
    selected: ItemCategory?,
    onSelect: (ItemCategory?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
      expanded = expanded && enabled,
      onExpandedChange = { if (enabled) expanded = it },
      modifier = modifier) {
        OutlinedTextField(
            value = stringResource(selected.labelOrNoneRes()),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.fridge_item_category_optional)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag(C.Tag.fridge_category_input))
        ExposedDropdownMenu(
            expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
              (listOf(null) + ItemCategory.entries).forEach { category ->
                DropdownMenuItem(
                    text = { Text(stringResource(category.labelOrNoneRes())) },
                    onClick = {
                      onSelect(category)
                      expanded = false
                    },
                    modifier = Modifier.testTag(C.Tag.fridgeCategoryOption(category)))
              }
            }
      }
}
