// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow

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
      modifier = modifier.fillMaxWidth().height(ComponentLayout.buttonHeight),
  ) {
    ButtonLabel(text)
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
              .height(ComponentLayout.buttonHeight)
              .shadow(ComponentLayout.secondaryButtonElevation, MaterialTheme.shapes.medium),
  ) {
    ButtonLabel(text)
  }
}

/** A one-line button label in the button text style. */
@Composable
private fun ButtonLabel(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis)
}
