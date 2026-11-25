// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.ui.unit.dp

/** Sizes used only by the household screens. Shared sizes live in `Dimens`. */
internal object HouseholdLayout {
  /** Number of steps of the onboarding flow shown by the step indicator. */
  const val ONBOARDING_STEP_COUNT = 3

  /** Onboarding step of the household choice screen. */
  const val CHOICE_STEP = 1

  /** Onboarding step of the create form and of the join screen, reached from the choice. */
  const val FORM_STEP = 2

  /** Onboarding step of the "household ready" view, the last one before the app. */
  const val READY_STEP = 3

  // Choice cards on the household choice screen.
  val choiceIconBoxSize = 52.dp

  // Invite code cells on the join screen.
  val codeCellWidth = 48.dp
  val codeCellHeight = 58.dp

  // "Household ready" view after creating a household.
  val readyBadgeSize = 88.dp
  val readyBadgeIconSize = 40.dp
  val inviteCharHeight = 46.dp

  // Rows of the members list.
  val memberAvatarSize = 44.dp
  val memberAvatarIconSize = 24.dp
}
