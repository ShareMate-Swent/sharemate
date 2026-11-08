// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpirationPreferencesTest {
  @Test
  fun defaultsKeepTheHistoricalThreeDayWindow() {
    val defaults = ExpirationPreferences()

    assertEquals(3, defaults.soonDays)
    assertEquals(1, defaults.soonRepeatDays)
    assertEquals(1, defaults.expiredRepeatDays)
  }

  @Test
  fun sanitizedClampsEveryValueIntoItsRange() {
    val result =
        ExpirationPreferences(soonDays = 99, soonRepeatDays = 0, expiredRepeatDays = 31).sanitized()

    assertEquals(ExpirationPreferences(60, 1, 30), result)
    assertEquals(1, ExpirationPreferences(soonDays = -4).sanitized().soonDays)
  }

  @Test
  fun sanitizedKeepsNeverAsNull() {
    val result = ExpirationPreferences(soonRepeatDays = null, expiredRepeatDays = null).sanitized()

    assertEquals(null, result.soonRepeatDays)
    assertEquals(null, result.expiredRepeatDays)
  }
}
