// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

/** Stores each device's current push token, so a server can reach it later. */
interface PushTokenRepository {
  /**
   * Saves [token] as the current push token of [deviceId], for [userId]. Replaces any value already
   * saved for that device; a device never has more than one token on record.
   *
   * @throws IllegalArgumentException if an argument is blank or [token] exceeds the accepted length
   */
  suspend fun saveToken(userId: String, deviceId: String, token: String)

  /**
   * Removes the token of [deviceId] for [userId], so this device is no longer targeted for that
   * user. Removing a token that does not exist succeeds.
   *
   * @throws IllegalArgumentException if [userId] or [deviceId] is blank
   */
  suspend fun deleteToken(userId: String, deviceId: String)
}
