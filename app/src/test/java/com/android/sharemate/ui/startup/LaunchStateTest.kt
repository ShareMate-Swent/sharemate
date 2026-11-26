// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import org.junit.Assert.*
import org.junit.Test

class LaunchStateTest {
  @Test
  fun progressWaitsForEachRequiredStage() {
    val state = LaunchState()
    assertEquals(0, state.progress)
    state.stages[LaunchStage.AUTH] = LaunchStatus.CONTINUE
    assertEquals(30, state.progress)
    state.stages[LaunchStage.HOUSEHOLD] = LaunchStatus.CONTINUE
    assertEquals(60, state.progress)
    state.stages[LaunchStage.DESTINATION] = LaunchStatus.READY
    assertEquals(100, state.progress)
  }

  @Test
  fun signedOutAndOnboardingDoNotWaitForFridge() {
    assertEquals(100, launchProgress(mapOf(LaunchStage.AUTH to LaunchStatus.READY)))
    assertEquals(
        100,
        launchProgress(
            mapOf(
                LaunchStage.AUTH to LaunchStatus.CONTINUE,
                LaunchStage.HOUSEHOLD to LaunchStatus.READY)))
  }

  @Test
  fun downstreamReadinessCannotBypassRestoringSession() {
    assertEquals(0, launchProgress(mapOf(LaunchStage.DESTINATION to LaunchStatus.READY)))
  }

  @Test
  fun errorsNeverCompleteAndCanRecover() {
    val state = LaunchState()
    state.stages[LaunchStage.AUTH] = LaunchStatus.CONTINUE
    state.stages[LaunchStage.HOUSEHOLD] = LaunchStatus.ERROR
    assertTrue(state.failed)
    assertEquals(30, state.progress)
    state.stages[LaunchStage.HOUSEHOLD] = LaunchStatus.READY
    assertFalse(state.failed)
    assertEquals(100, state.progress)
  }
}
