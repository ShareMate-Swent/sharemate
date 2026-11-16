// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

/** Stores each device's current push token, so a server can reach it later. */
interface PushTokenRepository {
  /**
   * Saves [token] as the current push token of [deviceId], for [userId]. Replaces any value already
   * saved for that device; a device never has more than one token on record.
   */
  suspend fun saveToken(userId: String, deviceId: String, token: String)
}
