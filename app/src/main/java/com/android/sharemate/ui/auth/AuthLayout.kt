// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import androidx.compose.ui.unit.dp

/** Sizes used only by the authentication screens. Shared sizes live in `Dimens`. */
internal object AuthLayout {
  /** Widest the entry screens grow on tablets and in landscape. */
  val maxContentWidth = 520.dp

  /** Diameter of the round back button. */
  val backButtonSize = 44.dp

  /** Height of the welcome illustration. */
  val illustrationHeight = 300.dp

  /** Width of the fridge drawn in the welcome illustration. */
  val fridgeWidth = 150.dp

  /** Height of that fridge. */
  val fridgeHeight = 300.dp

  /** Corner radius of that fridge. */
  val fridgeCornerRadius = 36.dp

  /** Height of the freezer door, from the top of the fridge. */
  val freezerHeight = 105.dp

  /** Width of a door handle. */
  val handleWidth = 6.dp

  /** Length of the freezer door handle. */
  val freezerHandleLength = 36.dp

  /** Length of the fridge door handle. */
  val fridgeHandleLength = 54.dp

  /** Distance of the handles from the left side of the fridge. */
  val handleInset = 22.dp

  /** Shadow under the fridge. */
  val fridgeElevation = 12.dp

  /** Size of the round shapes behind the fridge. */
  val blobLarge = 90.dp

  /** Size of the rounded squares behind the fridge. */
  val blobSmall = 64.dp

  /** Diameter of the white disc behind the Google logo. */
  val googleBadgeSize = 28.dp

  /** Size of the Google logo. */
  val googleLogoSize = 18.dp
}
