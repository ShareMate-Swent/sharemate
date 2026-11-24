// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.android.sharemate.ui.theme.Dimens

/**
 * A red banner with an error icon, shown under a household form when an action failed.
 *
 * @param message The text to show.
 * @param testTag Test tag of the message text.
 * @param modifier Applied to the banner.
 */
@Composable
internal fun HouseholdErrorBanner(message: String, testTag: String, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = colors.errorContainer,
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        modifier = Modifier.padding(Dimens.spacingM),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = Icons.Outlined.ErrorOutline,
          contentDescription = null,
          tint = colors.error,
          modifier = Modifier.size(Dimens.iconSmall),
      )
      Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = colors.error,
          modifier = Modifier.testTag(testTag),
      )
    }
  }
}
