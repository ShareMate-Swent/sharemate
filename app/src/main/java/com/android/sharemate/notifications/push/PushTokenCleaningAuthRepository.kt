// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An [AuthRepository] that removes this device's push token before it signs the user out, so the
 * account that just left is no longer targeted on this device.
 *
 * The removal needs the user to still be signed in, so [signOut] waits for it, but never longer
 * than [cleanupTimeoutMillis]: with no connection Firestore cannot confirm the deletion, and
 * signing out must not hang on it. In that case the token stays on record until it is replaced or
 * pruned server-side. The sign-out is skipped if someone else signed in meanwhile.
 */
class PushTokenCleaningAuthRepository(
    private val delegate: AuthRepository,
    private val currentUser: CurrentUserIdProvider,
    private val registrar: PushTokenRegistrar,
    private val scope: CoroutineScope,
    private val cleanupTimeoutMillis: Long,
) : AuthRepository by delegate {

  override fun signOut() {
    val userId = currentUser.currentUserId()
    if (userId == null) {
      delegate.signOut()
      return
    }
    scope.launch {
      withTimeoutOrNull(cleanupTimeoutMillis) { registrar.unregister(userId) }
      if (currentUser.currentUserId() == userId) delegate.signOut()
    }
  }
}
