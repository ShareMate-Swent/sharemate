// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.utils

import org.junit.Assert.*
import org.junit.Test

class EmulatorResetRetryTest {
  @Test
  fun transientConflictRetriesWithBackoff() {
    val pauses = mutableListOf<Long>()
    val statuses = ArrayDeque(listOf(409, 409, 200))
    retryEmulatorConflict(pauses::add) { statuses.removeFirst() }
    assertEquals(listOf(250L, 500L), pauses)
    assertTrue(statuses.isEmpty())
  }

  @Test
  fun persistentConflictFailsAfterFourAttempts() {
    var attempts = 0
    val pauses = mutableListOf<Long>()
    val failure =
        assertThrows(IllegalStateException::class.java) {
          retryEmulatorConflict(pauses::add) {
            attempts++
            409
          }
        }
    assertEquals(4, attempts)
    assertEquals(listOf(250L, 500L, 1000L), pauses)
    assertTrue(failure.message!!.contains("HTTP 409"))
  }

  @Test
  fun successDoesNotWaitAndOtherFailuresPropagate() {
    retryEmulatorConflict({ error("Unexpected retry") }) { 204 }
    val denied = IllegalStateException("HTTP 403")
    assertSame(
        denied,
        assertThrows(IllegalStateException::class.java) {
          retryEmulatorConflict({ error("Unexpected retry") }) { throw denied }
        })
  }
}
