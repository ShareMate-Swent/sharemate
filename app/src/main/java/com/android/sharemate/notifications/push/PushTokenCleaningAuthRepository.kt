// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An [AuthRepository] that removes this device's push token before it signs the user out, so the
 * account that just left is no longer targeted on this device.
 *
 * The removal needs the user to still be signed in, so [signOut] waits for it, but never longer
 * than [cleanupTimeoutMillis]: with no connection Firestore cannot confirm the deletion, and
 * signing out must not hang on it. In that case the token stays on record until it is replaced or
 * pruned server-side.
 *
 * The removal and the sign-out run under the registrar's lock, so a registration running at the
 * same time cannot save the token again in between: it either finishes first (and its write is then
 * removed) or starts after the sign-out and finds nobody signed in.
 *
 * Because the sign-out completes later than the call, whoever shows the sign-in screen must keep it
 * disabled until [awaitSignOut] returns (see [awaitingSignOut]). Otherwise the same account could
 * sign back in and the late sign-out would end the new session. The user check below only covers a
 * different account signing in meanwhile.
 */
class PushTokenCleaningAuthRepository(
    private val delegate: AuthRepository,
    private val currentUser: CurrentUserIdProvider,
    private val registrar: PushTokenRegistrar,
    private val scope: CoroutineScope,
    private val cleanupTimeoutMillis: Long,
) : AuthRepository by delegate {
  @Volatile private var pendingSignOut: Job? = null

  override fun signOut() {
    val userId = currentUser.currentUserId()
    if (userId == null) {
      delegate.signOut()
      return
    }
    pendingSignOut = scope.launch { removeTokenThenSignOut(userId) }
  }

  private suspend fun removeTokenThenSignOut(userId: String) {
    val finished =
        withTimeoutOrNull(cleanupTimeoutMillis) {
          registrar.unregisterThen(userId) { signOutIfStillSignedIn(userId) }
          true
        }
    // Only when the lock was never taken (held by a stuck registration); a timeout during the
    // removal has already signed out under the lock, which makes this a no-op.
    if (finished == null) signOutIfStillSignedIn(userId)
  }

  private fun signOutIfStillSignedIn(userId: String) {
    if (currentUser.currentUserId() == userId) delegate.signOut()
  }

  /** Suspends until the sign-out started by [signOut] has finished; returns at once if none. */
  suspend fun awaitSignOut() {
    pendingSignOut?.join()
  }

  /**
   * Wraps [clearCredentialState] so it only returns once the sign-out has finished, even when
   * clearing the credentials fails. Hand the result to the screen that blocks authentication while
   * it runs.
   */
  fun awaitingSignOut(clearCredentialState: suspend () -> Unit): suspend () -> Unit = {
    try {
      clearCredentialState()
    } finally {
      awaitSignOut()
    }
  }
}
