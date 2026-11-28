// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.compose.ui.unit.dp

/** Sizes used only by the Settings screen. Shared sizes live in `Dimens`. */
internal object SettingsLayout {
  val horizontalPadding = 20.dp
  val sectionSpacing = 28.dp
  val cardCornerRadius = 24.dp
  val cardIconBoxSize = 44.dp
  val cardIconBoxCornerRadius = 12.dp
  val avatarSize = 72.dp
  val avatarIconSize = 40.dp
  /** Camera badge on the avatar, showing that tapping it changes the photo. */
  val photoBadgeSize = 24.dp
  val photoBadgePadding = 4.dp
  val dialogProgressSize = 24.dp

  // Edit profile page.
  /** Size of the profile photo on the Edit profile page. */
  val editAvatarSize = 88.dp

  /** Icon size of the placeholder avatar on the Edit profile page. */
  val editAvatarIconSize = 48.dp

  /** Size of the round camera badge that shows the photo can be changed. */
  val editBadgeSize = 32.dp

  /** Size of the camera icon inside the badge. */
  val editBadgeIconSize = 16.dp
}
