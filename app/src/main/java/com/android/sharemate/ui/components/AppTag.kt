// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.sharemate.ui.theme.StatusColor

/**
 * A small pastel label, such as an expiry date or an owner.
 *
 * @param text The label.
 * @param colors The pastel pair, usually from `ShareMateTheme.statusColors`.
 * @param modifier Applied to the tag.
 */
@Composable
fun AppTag(text: String, colors: StatusColor, modifier: Modifier = Modifier) {
  SingleLineText(
      text = text,
      color = colors.content,
      style = MaterialTheme.typography.labelSmall,
      modifier =
          modifier
              .background(colors.container, CircleShape)
              .padding(
                  horizontal = ComponentLayout.tagHorizontalPadding,
                  vertical = ComponentLayout.tagVerticalPadding))
}
