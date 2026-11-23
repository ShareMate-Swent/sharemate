// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import com.android.sharemate.ui.theme.Dimens

/**
 * The main action of a screen: a full-width button in the primary color.
 *
 * @param text The button's label.
 * @param onClick Called when the button is tapped.
 * @param modifier Applied to the button.
 * @param enabled Whether the button can be tapped.
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      modifier = modifier.fillMaxWidth().height(Dimens.buttonHeight),
  ) {
    SingleLineText(text, MaterialTheme.typography.labelLarge)
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
