// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.android.sharemate.ui.theme.Dimens

/** What an [AppBanner] tells the user, which sets its colors and icon. */
enum class AppBannerTone {
  /** Something is fine or was done, in the primary container colors. */
  INFO,

  /** Something needs attention but nothing failed, in the tertiary container colors. */
  WARNING,

  /** Something failed, in the error container colors. */
  ERROR
}

/**
 * A rounded banner with an icon and a message, shown under a form or at the top of a screen. It is
 * a polite live region, so screen readers announce it when it appears.
 *
 * @param message The text to show.
 * @param tone Whether the banner is informative, a warning or an error.
 * @param testTag Test tag of the message text.
 * @param modifier Applied to the banner.
 */
@Composable
fun AppBanner(
    message: String,
    tone: AppBannerTone,
    testTag: String,
    modifier: Modifier = Modifier,
) {
  val look = tone.look(MaterialTheme.colorScheme)
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = look.container,
      // Polite live region: TalkBack reads the banner out when it appears.
      modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Row(
        modifier = Modifier.padding(Dimens.spacingM),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = look.icon,
          contentDescription = null,
          tint = look.content,
          modifier = Modifier.size(Dimens.iconSmall),
      )
      Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = look.content,
          modifier = Modifier.testTag(testTag),
      )
    }
  }
}

/** Colors and icon of one [AppBannerTone]. */
private class BannerLook(val container: Color, val content: Color, val icon: ImageVector)

private fun AppBannerTone.look(colors: ColorScheme): BannerLook =
    when (this) {
      AppBannerTone.INFO ->
          BannerLook(
              colors.primaryContainer, colors.onPrimaryContainer, Icons.Outlined.CheckCircleOutline)
      AppBannerTone.WARNING ->
          BannerLook(
              colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Outlined.WarningAmber)
      AppBannerTone.ERROR ->
          BannerLook(colors.errorContainer, colors.onErrorContainer, Icons.Outlined.ErrorOutline)
    }
