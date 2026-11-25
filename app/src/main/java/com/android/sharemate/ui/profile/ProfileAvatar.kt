// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter

/**
 * A round account photo, drawn over a placeholder that shows while it loads, if it fails, or if
 * there is no [photoUrl]. Used by the Settings header and the members list, so both follow the same
 * rule.
 *
 * @param size Diameter of the avatar.
 * @param iconSize Size of the placeholder icon.
 * @param contentDescription Description of the photo; the placeholder is decorative.
 * @param photoTestTag Test tag of the photo, present whenever there is a [photoUrl].
 * @param placeholderTestTag Test tag of the placeholder icon, present until the photo loads.
 * @param containerColor Background of the placeholder.
 * @param iconTint Color of the placeholder icon.
 * @param icon The placeholder icon.
 */
@Composable
internal fun ProfileAvatar(
    photoUrl: String?,
    size: Dp,
    iconSize: Dp,
    contentDescription: String?,
    photoTestTag: String,
    placeholderTestTag: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    icon: ImageVector = Icons.Filled.Person,
) {
  val painter = photoUrl?.let { rememberAsyncImagePainter(model = it) }
  Box(
      modifier = modifier.size(size).clip(CircleShape).background(containerColor),
      contentAlignment = Alignment.Center,
  ) {
    if (painter?.state !is AsyncImagePainter.State.Success) {
      Icon(
          imageVector = icon,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(iconSize).testTag(placeholderTestTag),
      )
    }
    if (painter != null) {
      Image(
          painter = painter,
          contentDescription = contentDescription,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().testTag(photoTestTag),
      )
    }
  }
}
