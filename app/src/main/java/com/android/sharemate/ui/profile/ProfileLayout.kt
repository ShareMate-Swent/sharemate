// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.compose.ui.unit.dp

/** Sizes used only by the profile photo screens. Shared sizes live in `Dimens`. */
internal object ProfileLayout {
  /** Diameter of the avatar preview on the photo step. */
  val stepAvatarSize = 168.dp
  /** Size of the placeholder icon inside that avatar. */
  val stepAvatarIconSize = 56.dp
  /** Width of the dashed ring around that avatar. */
  val stepAvatarRingWidth = 2.dp
  /** Opacity of that ring, drawn in the muted text color. */
  const val STEP_AVATAR_RING_ALPHA = 0.45f
  /** Length of each dash of that ring, and of the gap after it. */
  val stepAvatarDashLength = 8.dp
  /** Diameter of the "+" badge on that avatar. */
  val stepBadgeSize = 44.dp
  /** Size of the icon on the camera and gallery cards. */
  val sourceIconSize = 24.dp
}
