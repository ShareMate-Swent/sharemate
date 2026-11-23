// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.ui.unit.dp

/** Sizes of the shared components, from the `Components` frame of the redesign Figma file. */
internal object ComponentLayout {
  /** Height of the primary and secondary buttons. */
  val buttonHeight = 56.dp
  /** Elevation of the secondary button. */
  val secondaryButtonElevation = 1.dp
  /** Visible height of a chip; its touch target stays at the Material minimum. */
  val chipHeight = 40.dp
  /** Horizontal padding inside a chip. */
  val chipHorizontalPadding = 18.dp
  /** Border width of an unselected chip. */
  val chipBorderWidth = 1.dp
  /** Horizontal padding inside a tag. */
  val tagHorizontalPadding = 10.dp
  /** Vertical padding inside a tag. */
  val tagVerticalPadding = 5.dp
  /** Space between the parts of a list row. */
  val listRowSpacing = 14.dp
  /** Vertical padding of a list row. */
  val listRowVerticalPadding = 12.dp
  /** Space between the title and subtitle of a list row. */
  val listRowTextSpacing = 2.dp
  /** Height of the floating navigation bar. */
  val navBarHeight = 64.dp
  /** Horizontal padding inside the navigation bar. */
  val navBarHorizontalPadding = 12.dp
  /** Shadow of the navigation bar. */
  val navBarElevation = 12.dp
  /** Visible size of an unselected navigation item; its touch target is the Material minimum. */
  val navItemSize = 44.dp
  /** Horizontal padding of the selected navigation item. */
  val navSelectedHorizontalPadding = 14.dp
  /** Space between the icon and label of the selected navigation item. */
  val navSelectedSpacing = 6.dp
  /** Size of a navigation icon. */
  val navIconSize = 22.dp
  /** Size of the central add button. */
  val navAddSize = 48.dp
}
