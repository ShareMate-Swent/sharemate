// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import androidx.compose.ui.unit.dp

/** Dimensions used only by the chat screen; shared spacings come from `Dimens`. */
internal object ChatLayout {
  const val composerMaxLines = 4

  /** Most avatars shown in the header. */
  const val maxHeaderAvatars = 3

  /** Most characters in an avatar's initials. */
  const val maxInitials = 2

  val messageSpacing = 6.dp
  val bubbleMaxWidth = 280.dp
  val bubbleCornerRadius = 18.dp

  /** Diameter of the avatar next to a message and in the header. */
  val avatarSize = 32.dp

  /** Diameter of the colored circle holding a fridge activity's icon. */
  val activityIconContainerSize = 24.dp

  /** Size of a fridge activity's icon. */
  val activityIconSize = 14.dp

  /** Diameter of the circle behind the empty-state icon. */
  val emptyIconContainerSize = 72.dp

  /** Size of the empty-state icon. */
  val emptyIconSize = 28.dp

  /** Size of the send button. */
  val sendButtonSize = 40.dp
}
