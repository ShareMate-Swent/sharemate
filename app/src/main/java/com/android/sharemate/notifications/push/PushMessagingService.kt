// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * Entry point FCM calls directly, outside any activity. Showing and handling incoming push messages
 * is not implemented yet; this service only keeps the device's token on record.
 */
class PushMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    PushTokenRefreshScheduler.enqueue(applicationContext, token)
  }
}
