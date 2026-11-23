// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.android.sharemate.ui.theme.ShareMateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Application root: keeps the startup overlay and destination under the same theme. */
@Composable
fun ShareMateLaunch(content: @Composable () -> Unit) {
  ShareMateTheme { LaunchHost(content) }
}

/**
 * Compose the real destination behind the reveal so its first load is not restarted on dismissal.
 */
@Composable
fun LaunchHost(content: @Composable () -> Unit) {
  var finished by rememberSaveable { mutableStateOf(false) }
  var attempt by rememberSaveable { mutableIntStateOf(0) }
  val state = remember(attempt) { LaunchState() }
  var barVisible by remember(attempt) { mutableStateOf(false) }
  var timedOut by remember(attempt) { mutableStateOf(false) }
  val progress = remember(attempt) { Animatable(0f) }
  val transition = remember(attempt) { Animatable(0f) }
  LaunchedEffect(state, finished) {
    if (!finished) {
      val settled =
          withTimeoutOrNull(LaunchLayout.loadingTimeoutMillis) {
            snapshotFlow { state.progress == 100 || state.failed }.first { it }
          }
      if (settled == null) timedOut = true
    }
  }
  LaunchedEffect(barVisible, state.progress, state.failed, timedOut) {
    if (barVisible && !state.failed && !timedOut) {
      progress.animateTo(state.progress.toFloat(), tween(LaunchLayout.progressStepDurationMillis))
      if (state.progress == LaunchLayout.readyProgress) {
        withFrameNanos {}
        if (state.welcomeBrandBounds != null) {
          transition.animateTo(1f, tween(LaunchLayout.transitionDurationMillis))
        }
        finished = true
      }
    }
  }
  CompositionLocalProvider(
      LocalLaunchState provides if (finished) null else state,
      LocalLaunchAttempt provides attempt,
      LocalLaunchVisible provides !finished) {
        Box(Modifier.fillMaxSize()) {
          Box(
              if (finished) Modifier.fillMaxSize()
              else
                  Modifier.fillMaxSize()
                      .graphicsLayer { alpha = transition.value }
                      .clearAndSetSemantics {}) {
                key(attempt) { content() }
              }
          if (!finished) {
            BackHandler {}
            // Block the destination without consuming gestures inside the interactive reveal.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                  awaitPointerEventScope {
                    while (true) {
                      awaitPointerEvent().changes.forEach { it.consume() }
                    }
                  }
                })
            key(attempt) {
              LaunchReveal(
                  progress = progress.value.toInt(),
                  failed = state.failed || timedOut,
                  onBarVisible = { barVisible = true },
                  onRetry = { attempt++ },
                  transition = transition.value,
                  destinationBounds = state.welcomeBrandBounds,
                  modifier =
                      Modifier.fillMaxSize()
                          .background(Color.White.copy(alpha = 1f - transition.value)))
            }
          }
        }
      }
}
