// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.android.sharemate.ui.theme.Dimens

/**
 * A white rounded card that groups content, such as list rows.
 *
 * @param modifier Applied to the card.
 * @param content The card's content.
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = Dimens.spacingM), content = content)
  }
}

/**
 * A list row: optional leading content, a title with an optional subtitle, and optional trailing
 * content. Long text is cut with an ellipsis, so the trailing content always stays visible.
 *
 * @param title The main text.
 * @param modifier Applied to the row.
 * @param subtitle The secondary text, if any.
 * @param onClick Called when the row is tapped; without it the row is not clickable.
 * @param leading Shown before the text, usually a [LetterAvatar].
 * @param trailing Shown after the text, usually an [AppTag] or a date.
 */
@Composable
fun AppListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
              .padding(vertical = ComponentLayout.listRowVerticalPadding),
      horizontalArrangement = Arrangement.spacedBy(ComponentLayout.listRowSpacing),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    leading?.invoke()
    Column(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(ComponentLayout.listRowTextSpacing)) {
          Text(
              text = title,
              style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis)
          subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
          }
        }
    trailing?.invoke()
  }
}
