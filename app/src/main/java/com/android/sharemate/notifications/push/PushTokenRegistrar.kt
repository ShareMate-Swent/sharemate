// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.util.Log
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CancellationException

/**
 * Keeps this device's push token saved for whichever user is currently signed in.
 *
 * Nobody is notified by this class; it only maintains the record a server-side sender will later
 * read. Every failure is logged and reported through the returned flag, so the caller (a
 * WorkManager job) can retry exactly what failed.
 */
class PushTokenRegistrar(
    private val currentUser: CurrentUserIdProvider,
    private val tokenRepository: PushTokenRepository,
    private val deviceId: String,
    private val fetchToken: suspend () -> String,
) {
  /**
   * Saves a push token for the signed-in user. Uses [knownToken] when FCM just issued one, and
   * otherwise reads the current FCM token, so the value written is always the latest one.
   *
   * @return false only when something failed and is worth retrying; true when the token was saved
   *   or there was nobody to save it for (the next session start registers it).
   */
  suspend fun register(knownToken: String?): Boolean {
    val userId = currentUser.currentUserId() ?: return true
    val token = knownToken ?: readCurrentToken() ?: return false
    return attempt(SAVE_FAILED_MESSAGE) { tokenRepository.saveToken(userId, deviceId, token) }
  }

  /**
   * Removes this device's token for [userId], so the previous account is not targeted once someone
   * else uses the device. Must run while [userId] is still signed in.
   *
   * @return true when the token is gone, false when the removal failed
   */
  suspend fun unregister(userId: String): Boolean =
      attempt(DELETE_FAILED_MESSAGE) { tokenRepository.deleteToken(userId, deviceId) }

  private suspend fun readCurrentToken(): String? =
      try {
        fetchToken()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.w(TAG, READ_FAILED_MESSAGE, error)
        null
      }

  private suspend fun attempt(failureMessage: String, action: suspend () -> Unit): Boolean =
      try {
        action()
        true
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.w(TAG, failureMessage, error)
        false
      }

  private companion object {
    const val TAG = "PushTokenRegistrar"
    const val READ_FAILED_MESSAGE = "Unable to read the current push token"
    const val SAVE_FAILED_MESSAGE = "Unable to save the push token"
    const val DELETE_FAILED_MESSAGE = "Unable to remove the push token"
  }
}
