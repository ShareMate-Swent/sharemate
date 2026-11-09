// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.user

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpirationPreferencesProviderTest {
  private val repository = FakeExpirationPreferencesRepository()
  private val provider = ExpirationPreferencesProvider(repository)
  private val saved =
      ExpirationPreferences(soonDays = 9, soonRepeatDays = 4, expiredRepeatDays = null)

  @Test
  fun getReturnsTheStoredPreferences() = runTest {
    repository.save(USER, saved)

    assertEquals(saved, provider.get(USER))
  }

  @Test
  fun getFallsBackToTheDefaultsWhenTheReadFails() = runTest {
    repository.readFailure = IOException("offline")

    assertEquals(ExpirationPreferences(), provider.get(USER))
  }

  @Test
  fun getDoesNotSwallowCancellation() = runTest {
    repository.readFailure = CancellationException("cancelled")

    val outcome = runCatching { provider.get(USER) }

    assertTrue(outcome.exceptionOrNull() is CancellationException)
  }

  @Test
  fun observeEmitsTheStoredPreferences() = runTest {
    repository.save(USER, saved)

    assertEquals(saved, provider.observe(USER).first())
  }

  @Test
  fun observeEmitsTheDefaultsThenEndsWhenTheReadFails() = runTest {
    repository.readFailure = IOException("denied")

    assertEquals(listOf(ExpirationPreferences()), provider.observe(USER).toList())
  }

  private companion object {
    const val USER = "alice"
  }
}
