// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics

private const val FALLBACK_INITIAL = "?"

/**
 * A rounded tile with the first letter of [name]. It is decorative: the name next to it is what
 * screen readers announce.
 *
 * @param name The name to take the letter from; a blank name shows "?".
 * @param modifier Applied to the avatar.
 * @param containerColor The tile's color.
 * @param contentColor The letter's color.
 */
@Composable
fun LetterAvatar(
    name: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
  Box(
      modifier
          .size(ComponentLayout.avatarSize)
          .background(containerColor, MaterialTheme.shapes.small)
          .clearAndSetSemantics {},
      contentAlignment = Alignment.Center) {
        Text(
            text = initialOf(name),
            color = contentColor,
            style = MaterialTheme.typography.titleMedium)
      }
}

/** The first character of [name] in upper case, keeping emoji whole; "?" when it is blank. */
internal fun initialOf(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return FALLBACK_INITIAL
  return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
}
