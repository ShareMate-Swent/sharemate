// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpirationPreferencesTest {
  private val soon = ExpirationPreferences.SOON_DAYS_RANGE
  private val repeat = ExpirationPreferences.REPEAT_DAYS_RANGE

  @Test
  fun defaultsKeepTheHistoricalThreeDayWindow() {
    val defaults = ExpirationPreferences()

    assertEquals(3, defaults.soonDays)
    assertEquals(1, defaults.soonRepeatDays)
    assertEquals(1, defaults.expiredRepeatDays)
  }

  @Test
  fun valuesOnTheBoundariesOfTheRangesAreAccepted() {
    ExpirationPreferences(soonDays = soon.first, soonRepeatDays = repeat.first)
    ExpirationPreferences(soonDays = soon.last, soonRepeatDays = repeat.last)
    ExpirationPreferences(expiredRepeatDays = repeat.first)
    ExpirationPreferences(expiredRepeatDays = repeat.last)
  }

  @Test
  fun neverIsAnAcceptedRepeat() {
    val never = ExpirationPreferences(soonRepeatDays = null, expiredRepeatDays = null)

    assertNull(never.soonRepeatDays)
    assertNull(never.expiredRepeatDays)
  }

  @Test
  fun soonDaysJustOutsideTheRangeAreRefused() {
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(soonDays = soon.first - 1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(soonDays = soon.last + 1)
    }
  }

  @Test
  fun soonRepeatJustOutsideTheRangeIsRefused() {
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(soonRepeatDays = repeat.first - 1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(soonRepeatDays = repeat.last + 1)
    }
  }

  @Test
  fun expiredRepeatJustOutsideTheRangeIsRefused() {
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(expiredRepeatDays = repeat.first - 1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences(expiredRepeatDays = repeat.last + 1)
    }
  }

  @Test
  fun copyCannotLeaveTheRanges() {
    assertThrows(IllegalArgumentException::class.java) {
      ExpirationPreferences().copy(soonDays = soon.last + 1)
    }
  }

  @Test
  fun coercedBringsEveryValueIntoItsRange() {
    val result =
        ExpirationPreferences.coerced(soonDays = 99, soonRepeatDays = 0, expiredRepeatDays = 31)

    assertEquals(ExpirationPreferences(soon.last, repeat.first, repeat.last), result)
    assertEquals(soon.first, ExpirationPreferences.coerced(-4, null, null).soonDays)
  }

  @Test
  fun coercedKeepsNeverAndValidValues() {
    val never = ExpirationPreferences.coerced(soon.first, null, null)
    val valid = ExpirationPreferences.coerced(7, 2, 5)

    assertNull(never.soonRepeatDays)
    assertNull(never.expiredRepeatDays)
    assertEquals(ExpirationPreferences(7, 2, 5), valid)
  }
}
