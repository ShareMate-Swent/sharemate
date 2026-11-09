// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.ui.unit.dp

/** Sizes used only by the household screens. Shared sizes live in `Dimens`. */
internal object HouseholdLayout {
  val cardCornerRadius = 28.dp
  val headerTopSpacing = 40.dp
  val logoSize = 44.dp
  val logoIconSize = 22.dp

  // Choice cards on the household choice screen.
  val choiceCardMinHeight = 156.dp
  val choiceIconBoxSize = 56.dp
  val choiceIconBoxCornerRadius = 16.dp
  val choiceArrowBoxSize = 36.dp
  val choiceArrowIconSize = 20.dp

  // Invite code cells on the join screen.
  val codeCellWidth = 48.dp
  val codeCellHeight = 58.dp
  val codeCellCornerRadius = 12.dp

  // "Household ready" view after creating a household.
  val readyBadgeSize = 96.dp
  val readyBadgeElevation = 8.dp
  val readyBadgeIconSize = 48.dp
  val readyCardVerticalPadding = 20.dp

  // Rows of the members list.
  val memberAvatarSize = 40.dp
  val memberAvatarIconSize = 24.dp
}
