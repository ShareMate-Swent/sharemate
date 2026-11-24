// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * One destination of the [FloatingNavBar].
 *
 * @property label The name of the destination, shown when selected and read by screen readers.
 * @property icon The destination's icon.
 * @property selected Whether this destination is open.
 * @property testTag The test tag of the item.
 * @property onClick Called when the item is tapped.
 */
data class FloatingNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val testTag: String,
    val onClick: () -> Unit,
)

/**
 * The central add button of the [FloatingNavBar].
 *
 * @property label What the button adds, read by screen readers.
 * @property testTag The test tag of the button.
 * @property onClick Called when the button is tapped.
 */
data class FloatingNavAction(val label: String, val testTag: String, val onClick: () -> Unit)

/**
 * The app's floating bottom bar. The selected item shows its label on a primary pill, the others
 * only their icon. With an [action], the add button sits in the middle of the items.
 *
 * @param items The destinations, in order.
 * @param modifier Applied to the bar.
 * @param action The central add button, if any.
 */
@Composable
fun FloatingNavBar(
    items: List<FloatingNavItem>,
    modifier: Modifier = Modifier,
    action: FloatingNavAction? = null,
) {
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = ComponentLayout.navBarElevation,
      modifier = modifier.fillMaxWidth().height(ComponentLayout.navBarHeight),
  ) {
    Row(
        Modifier.padding(horizontal = ComponentLayout.navBarHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val middle = (items.size + 1) / 2
      items.take(middle).forEach { NavItem(it) }
      action?.let { AddButton(it) }
      items.drop(middle).forEach { NavItem(it) }
    }
  }
}

/** A destination: a labelled pill when selected, otherwise a plain icon. */
@Composable
private fun RowScope.NavItem(item: FloatingNavItem) {
  val colors = MaterialTheme.colorScheme
  // Only the selected pill may shrink, so a long label never pushes the icons out of the bar.
  val sizing = if (item.selected) Modifier.weight(1f, fill = false) else Modifier
  Surface(
      selected = item.selected,
      onClick = item.onClick,
      shape = MaterialTheme.shapes.small,
      color = if (item.selected) colors.primary else colors.surface,
      contentColor = if (item.selected) colors.onPrimary else colors.onSurfaceVariant,
      modifier =
          sizing.minimumInteractiveComponentSize().testTag(item.testTag).semantics {
            role = Role.Tab
            if (!item.selected) contentDescription = item.label
          },
  ) {
    if (item.selected) {
      Row(
          Modifier.height(ComponentLayout.navItemSize)
              .padding(horizontal = ComponentLayout.navSelectedHorizontalPadding),
          horizontalArrangement = Arrangement.spacedBy(ComponentLayout.navSelectedSpacing),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(item.icon, null, Modifier.size(ComponentLayout.navIconSize))
        SingleLineText(item.label, MaterialTheme.typography.labelMedium)
      }
    } else {
      Box(Modifier.size(ComponentLayout.navItemSize), contentAlignment = Alignment.Center) {
        Icon(item.icon, null, Modifier.size(ComponentLayout.navIconSize))
      }
    }
  }
}

/** The round add button in the middle of the bar. */
@Composable
private fun AddButton(action: FloatingNavAction) {
  Surface(
      onClick = action.onClick,
      shape = CircleShape,
      color = MaterialTheme.colorScheme.inverseSurface,
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      modifier = Modifier.testTag(action.testTag),
  ) {
    Box(Modifier.size(ComponentLayout.navAddSize), contentAlignment = Alignment.Center) {
      Icon(Icons.Filled.Add, action.label, Modifier.size(ComponentLayout.navIconSize))
    }
  }
}
