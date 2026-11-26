// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Text kept on one line and cut with an ellipsis, the truncation rule of the shared components.
 *
 * @param text The text to show.
 * @param style The text style.
 * @param modifier Applied to the text.
 * @param color The text color; by default it comes from [style] or the local content color.
 */
@Composable
internal fun SingleLineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
  Text(
      text = text,
      color = color,
      style = style,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier)
}
