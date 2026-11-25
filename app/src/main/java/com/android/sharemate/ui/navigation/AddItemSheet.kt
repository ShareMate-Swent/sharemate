// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppSecondaryButton
import com.android.sharemate.ui.components.ComponentLayout
import com.android.sharemate.ui.theme.Dimens

/** Sizes of the sheet opened by the add button of the bottom bar. */
private object AddItemSheetLayout {
  /** Size of the colored square behind the icon of an option. */
  val iconTileSize = 44.dp
  /** Size of the icon of an option, the same as in the bottom bar. */
  val iconSize = ComponentLayout.navIconSize
}

/** Test tags of the sheet opened by the add button of the bottom bar. */
object AddItemSheetTestTags {
  /** The sheet itself. */
  const val SHEET = "add_item_sheet"
  /** The button that closes the sheet without choosing anything. */
  const val CANCEL = "add_item_sheet_cancel"

  /** The option that starts [method]. */
  fun option(method: AddItemMethod) = "add_item_sheet_option_${method.name.lowercase()}"
}

/**
 * The sheet opened by the add button of the bottom bar, with the ways to add an item. Choosing one
 * reports it; Cancel, a tap outside the sheet or a swipe down only close it.
 *
 * @param onChoose Called with the way the user chose.
 * @param onDismiss Called when the sheet closes without a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddItemSheet(onChoose: (AddItemMethod) -> Unit, onDismiss: () -> Unit) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      containerColor = MaterialTheme.colorScheme.background,
      modifier = Modifier.testTag(AddItemSheetTestTags.SHEET)) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = Dimens.screenHorizontalPadding,
                    end = Dimens.screenHorizontalPadding,
                    bottom = Dimens.spacingL),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
              Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                Text(
                    stringResource(R.string.nav_add_item),
                    style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.add_item_sheet_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              AddItemMethod.entries.forEach { method ->
                AddItemOption(method, onClick = { onChoose(method) })
              }
              AppSecondaryButton(
                  text = stringResource(R.string.fridge_cancel),
                  onClick = onDismiss,
                  modifier = Modifier.testTag(AddItemSheetTestTags.CANCEL))
            }
      }
}

/** How an option of the sheet looks: its icon, the colors of its tile and its texts. */
private class AddItemOptionStyle(
    val icon: ImageVector,
    val tile: Color,
    val iconColor: Color,
    @StringRes val title: Int,
    @StringRes val description: Int,
)

@Composable
private fun AddItemMethod.style(): AddItemOptionStyle {
  val colors = MaterialTheme.colorScheme
  return when (this) {
    AddItemMethod.BARCODE ->
        AddItemOptionStyle(
            Icons.Filled.QrCodeScanner,
            colors.primaryContainer,
            colors.onPrimaryContainer,
            R.string.add_item_barcode,
            R.string.add_item_barcode_description)
    AddItemMethod.MANUAL ->
        AddItemOptionStyle(
            Icons.Filled.Edit,
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            R.string.add_item_manual,
            R.string.add_item_manual_description)
    AddItemMethod.RECEIPT ->
        AddItemOptionStyle(
            Icons.AutoMirrored.Filled.ReceiptLong,
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            R.string.add_item_receipt,
            R.string.add_item_receipt_description)
  }
}

/**
 * One way to add an item: an icon on a colored tile, a title, a short description and a chevron.
 */
@Composable
private fun AddItemOption(method: AddItemMethod, onClick: () -> Unit) {
  val style = method.style()
  Surface(
      onClick = onClick,
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(Dimens.borderWidth, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth().testTag(AddItemSheetTestTags.option(method))) {
        Row(
            Modifier.padding(Dimens.spacingS),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
            verticalAlignment = Alignment.CenterVertically) {
              Surface(shape = MaterialTheme.shapes.small, color = style.tile) {
                Box(Modifier.size(AddItemSheetLayout.iconTileSize), Alignment.Center) {
                  Icon(
                      style.icon,
                      null,
                      Modifier.size(AddItemSheetLayout.iconSize),
                      tint = style.iconColor)
                }
              }
              Column(Modifier.weight(1f)) {
                Text(
                    stringResource(style.title),
                    style =
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    stringResource(style.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(
                  Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
      }
}
