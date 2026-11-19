// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.util.Log
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps this device's push token saved for whichever user is currently signed in.
 *
 * Nobody is notified by this class; it only maintains the record a server-side sender will later
 * read. Every failure is logged and reported through the returned flag, so the caller (a
 * WorkManager job) can retry exactly what failed.
 *
 * The activity and the WorkManager job each build their own registrar, so [operations] must be the
 * same lock for all of them: it orders a registration against a sign-out cleanup, which would
 * otherwise let a token be saved again between its removal and the sign-out.
 */
class PushTokenRegistrar(
    private val currentUser: CurrentUserIdProvider,
    private val tokenRepository: PushTokenRepository,
    private val deviceId: String,
    private val operations: Mutex,
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
    if (currentUser.currentUserId() == null) return true
    val token = knownToken ?: readCurrentToken() ?: return false
    return operations.withLock {
      // Read again under the lock: a sign-out cleanup may have finished while this waited.
      val userId = currentUser.currentUserId() ?: return@withLock true
      attempt(SAVE_FAILED_MESSAGE) { tokenRepository.saveToken(userId, deviceId, token) }
    }
  }

  /**
   * Removes this device's token for [userId], so the previous account is not targeted once someone
   * else uses the device, then runs [afterwards] (the sign-out) before any registration can run
   * again. [userId] must still be signed in. [afterwards] runs while the lock is still held, even
   * if the removal failed or was cancelled (for instance by the caller's timeout), so no
   * registration can slip in between the removal and the sign-out.
   *
   * @return true when the token is gone, false when the removal failed
   */
  suspend fun unregisterThen(userId: String, afterwards: () -> Unit): Boolean =
      operations.withLock {
        try {
          attempt(DELETE_FAILED_MESSAGE) { tokenRepository.deleteToken(userId, deviceId) }
        } finally {
          afterwards()
        }
      }

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
