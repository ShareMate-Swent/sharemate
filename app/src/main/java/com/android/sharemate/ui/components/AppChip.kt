// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * A rounded choice, such as a category filter. Selected, it uses the primary color; selected and
 * disabled, it uses the primary container so its label stays readable.
 *
 * @param label The chip's text.
 * @param selected Whether this choice is active.
 * @param onClick Called when the chip is tapped.
 * @param modifier Applied to the chip.
 * @param enabled Whether the chip can be tapped.
 */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  val colors = MaterialTheme.colorScheme
  val container =
      when {
        !selected -> colors.surface
        enabled -> colors.primary
        else -> colors.primaryContainer
      }
  val content =
      when {
        !selected -> colors.onSurface
        enabled -> colors.onPrimary
        else -> colors.onPrimaryContainer
      }
  Surface(
      selected = selected,
      onClick = onClick,
      enabled = enabled,
      shape = CircleShape,
      color = container,
      contentColor = content,
      border =
          if (selected) null else BorderStroke(ComponentLayout.chipBorderWidth, colors.outline),
      modifier = modifier.minimumInteractiveComponentSize(),
  ) {
    Box(
        Modifier.height(ComponentLayout.chipHeight)
            .padding(horizontal = ComponentLayout.chipHorizontalPadding),
        contentAlignment = Alignment.Center) {
          Text(
              text = label,
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis)
        }
  }
}
