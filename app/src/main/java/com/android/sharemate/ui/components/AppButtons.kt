// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import com.android.sharemate.ui.theme.Dimens

/**
 * The main action of a screen: a full-width button in the primary color.
 *
 * While [loading] the label is replaced by a progress indicator and the button cannot be tapped, so
 * a slow action is not started twice.
 *
 * @param text The button's label.
 * @param onClick Called when the button is tapped.
 * @param modifier Applied to the button.
 * @param enabled Whether the button can be tapped.
 * @param loading Whether the action is running.
 * @param loadingTestTag Test tag of the progress indicator shown while [loading].
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingTestTag: String? = null,
) {
  Button(
      onClick = onClick,
      enabled = enabled && !loading,
      shape = MaterialTheme.shapes.medium,
      modifier = modifier.fillMaxWidth().height(Dimens.buttonHeight),
  ) {
    if (loading) {
      CircularProgressIndicator(
          modifier = Modifier.size(Dimens.progressSize).then(loadingTestTag.asTestTag()),
          color = LocalContentColor.current,
          strokeWidth = Dimens.progressStrokeWidth,
      )
    } else {
      SingleLineText(text, MaterialTheme.typography.labelLarge)
    }
  }
}

/**
 * A secondary action: a full-width button on the surface color with a light shadow.
 *
 * @param text The button's label.
 * @param onClick Called when the button is tapped.
 * @param modifier Applied to the button.
 * @param enabled Whether the button can be tapped.
 */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surface,
              contentColor = MaterialTheme.colorScheme.onSurface),
      // A Material elevation would tint the surface, so the shadow is drawn separately.
      elevation = null,
      modifier =
          modifier
              .fillMaxWidth()
              .height(Dimens.buttonHeight)
              .shadow(ComponentLayout.secondaryButtonElevation, MaterialTheme.shapes.medium),
  ) {
    SingleLineText(text, MaterialTheme.typography.labelLarge)
  }
}

/** A test tag modifier for this tag, or no modifier when there is none. */
private fun String?.asTestTag(): Modifier = if (this == null) Modifier else Modifier.testTag(this)
