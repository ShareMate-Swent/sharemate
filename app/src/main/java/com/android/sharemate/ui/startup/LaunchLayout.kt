// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.compose.ui.unit.dp
import com.android.sharemate.ui.theme.ShareMateBrandColors

/** Dimensions, timing and colors shared by the startup reveal and its destination transition. */
internal object LaunchLayout {
  val progressWidth = 210.dp
  val progressHeight = 4.dp
  val progressGap = 12.dp
  val brandToProgressGap = 20.dp
  val brandWidth = 520.dp
  val brandHeight = 296.dp
  val logoSize = 166.dp
  val logoToNameGap = 30.dp
  val nameWidthFraction = .85f
  val nameMaxWidth = 440.dp
  val nameHeight = 100.dp
  val failurePadding = 24.dp
  const val loadingTimeoutMillis = 30_000L
  const val writingDurationMillis = 3_100
  const val logoDurationMillis = 900
  const val progressDurationMillis = 250
  const val progressStepDurationMillis = 650
  const val transitionDurationMillis = 900
  const val firstStageProgress = 30
  const val readyProgress = 100
  const val fallbackProgress = 90
  val nameDark = ShareMateBrandColors.darkName
  val nameMint = ShareMateBrandColors.mintName
  val progressTrack = ShareMateBrandColors.progressTrack
  val progressLabel = ShareMateBrandColors.progressLabel
}
