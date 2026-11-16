// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Entry point FCM calls directly, outside any activity. Showing and handling incoming push messages
 * is not implemented yet; this service only keeps the device's token on record.
 */
class PushMessagingService : FirebaseMessagingService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    scope.launch { PushTokenRegistrarFactory.create(applicationContext).onTokenRefreshed(token) }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
