// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.util.Log
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Keeps this device's push token saved for whichever user is currently signed in.
 *
 * Nobody is notified by this class; it only maintains the record a server-side sender will later
 * read. A failure here (no connection, a denied write, ...) never surfaces: the next
 * [registerCurrentToken] call or token refresh will try again.
 */
class PushTokenRegistrar(
    private val authRepository: AuthRepository,
    private val tokenRepository: PushTokenRepository,
    private val deviceId: String,
) {
  /**
   * Saves this device's current FCM token, read lazily through [fetchToken], for the signed-in
   * user. Does nothing if nobody is signed in. Call once per session, so a token issued before any
   * sign-in (or for a previous account) still ends up on record for the current user.
   */
  suspend fun registerCurrentToken(fetchToken: suspend () -> String) {
    val userId = signedInUserId() ?: return
    val token =
        try {
          fetchToken()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.w(TAG, "Unable to read the current push token", error)
          return
        }
    saveToken(userId, token)
  }

  /** Saves a [token] FCM just issued or rotated, for whoever is signed in right now, if anyone. */
  suspend fun onTokenRefreshed(token: String) {
    val userId = signedInUserId() ?: return
    saveToken(userId, token)
  }

  private suspend fun signedInUserId(): String? = authRepository.session.first()?.uid

  private suspend fun saveToken(userId: String, token: String) {
    try {
      tokenRepository.saveToken(userId, deviceId, token)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Log.w(TAG, "Unable to save the push token", error)
    }
  }

  private companion object {
    const val TAG = "PushTokenRegistrar"
  }
}
