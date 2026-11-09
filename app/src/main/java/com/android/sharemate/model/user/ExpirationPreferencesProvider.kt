// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.user

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The one way features read a user's [ExpirationPreferences] when they must keep working without
 * them, so that the fridge and the reminders always fall back to the same defaults. A preference
 * that cannot be read is logged and replaced by [ExpirationPreferences] defaults, never rethrown.
 */
class ExpirationPreferencesProvider(private val repository: ExpirationPreferencesRepository) {

  /**
   * Emits the user's preferences, then every change. If reading fails, logs the error and emits the
   * defaults, after which the flow ends.
   */
  fun observe(userId: String): Flow<ExpirationPreferences> =
      repository.observe(userId).catch { error ->
        Log.w(TAG, "Unable to observe the expiry preferences, using the defaults", error)
        emit(ExpirationPreferences())
      }

  /**
   * Reads the user's current preferences. If reading fails, logs the error and returns the
   * defaults. A cancelled caller is not treated as a failure.
   */
  suspend fun get(userId: String): ExpirationPreferences =
      try {
        repository.get(userId)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Log.w(TAG, "Unable to read the expiry preferences, using the defaults", error)
        ExpirationPreferences()
      }

  private companion object {
    const val TAG = "ExpirationPreferences"
  }
}
