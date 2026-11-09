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
) {
  val painter = photoUrl?.let { rememberAsyncImagePainter(model = it) }
  Box(
      modifier =
          modifier
              .size(size)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
  ) {
    if (painter?.state !is AsyncImagePainter.State.Success) {
      Icon(
          imageVector = Icons.Filled.Person,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
