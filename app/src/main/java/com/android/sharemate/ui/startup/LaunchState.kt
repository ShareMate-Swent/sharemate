// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/** Startup milestones reported by the authentication, household and destination flows. */
enum class LaunchStage {
  AUTH,
  HOUSEHOLD,
  DESTINATION
}

/** State of one startup milestone. */
enum class LaunchStatus {
  WAITING,
  CONTINUE,
  READY,
  ERROR
}

/** Only completed startup milestones advance progress. 100 means the destination is usable. */
fun launchProgress(states: Map<LaunchStage, LaunchStatus>): Int {
  for ((index, stage) in LaunchStage.entries.withIndex()) {
    when (states[stage] ?: LaunchStatus.WAITING) {
      LaunchStatus.WAITING,
      LaunchStatus.ERROR -> return index * LaunchLayout.firstStageProgress
      LaunchStatus.READY -> return LaunchLayout.readyProgress
      LaunchStatus.CONTINUE -> Unit
    }
  }
  return LaunchLayout.fallbackProgress
}

/** Mutable startup state shared by the launch overlay and the destination underneath it. */
class LaunchState {
  // Measured before dismissal so the reveal lands on the welcome screen's exact position.
  var welcomeBrandBounds by mutableStateOf<Rect?>(null)
  val stages = mutableStateMapOf<LaunchStage, LaunchStatus>()
  val progress: Int
    get() = launchProgress(stages)

  val failed: Boolean
    get() {
      for (stage in LaunchStage.entries) {
        when (stages[stage] ?: LaunchStatus.WAITING) {
          LaunchStatus.ERROR -> return true
          LaunchStatus.READY,
          LaunchStatus.WAITING -> return false
          LaunchStatus.CONTINUE -> Unit
        }
      }
      return false
    }
}

/** Current startup state, or null after the reveal has completed. */
val LocalLaunchState = staticCompositionLocalOf<LaunchState?> { null }
/** Whether the startup overlay is still covering the destination. */
val LocalLaunchVisible = staticCompositionLocalOf { false }
/** Retry generation used to reset startup-owned state after a failure. */
val LocalLaunchAttempt = staticCompositionLocalOf { 0 }

/** Reports one startup milestone without coupling the screen to the launch host. */
@Composable
fun ReportLaunch(stage: LaunchStage, status: LaunchStatus) {
  val state = LocalLaunchState.current
  SideEffect { state?.stages?.set(stage, status) }
}
