// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.theme

import androidx.compose.ui.unit.dp

/**
 * App-wide spacing and component sizes. Screens take their sizes from here, or from their feature's
 * layout object (like `FridgeLayout`) for sizes only that feature uses, instead of writing dp
 * values inline.
 */
object Dimens {
  // Spacing scale, for spacers, arrangements and padding.
  val spacingXxs = 4.dp
  val spacingXs = 8.dp
  val spacingS = 12.dp
  val spacingM = 16.dp
  val spacingL = 24.dp
  val spacingXl = 32.dp
  val spacingXxl = 48.dp

  // Padding around a screen's content.
  val screenHorizontalPadding = 24.dp
  val screenVerticalPadding = 16.dp

  // Cards and buttons.
  val cardPadding = 16.dp
  val cardElevation = 2.dp
  val borderWidth = 1.dp
  val focusedBorderWidth = 2.dp
  val buttonHeight = 48.dp

  // Icons and progress indicators.
  val iconSmall = 18.dp
  val progressSize = 20.dp
  val progressStrokeWidth = 2.dp

  /** Size of the confirmed photo preview attached to an incident draft. */
  val attachmentPreviewSize = 96.dp

  // Corner radii of the design system, used by the theme's shapes.
  /** Corner radius of the smallest elements, like badges. */
  val cornerRadiusXs = 8.dp

  /** Corner radius of small elements, like chips, icon tiles and menus. */
  val cornerRadiusS = 14.dp

  /** Corner radius of buttons and text fields. */
  val cornerRadiusM = 18.dp

  /** Corner radius of cards and lists. */
  val cornerRadiusL = 22.dp

  /** Corner radius of sheets, dialogs and highlighted cards. */
  val cornerRadiusXl = 24.dp
}
