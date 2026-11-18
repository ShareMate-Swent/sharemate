// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.google.firebase.auth.FirebaseAuth

/** Tells who is signed in right now, without observing the session. */
fun interface CurrentUserIdProvider {
  /** The signed-in user's id, or null when nobody is signed in. */
  fun currentUserId(): String?
}

/** [CurrentUserIdProvider] backed by [auth]. */
class FirebaseCurrentUserIdProvider(private val auth: FirebaseAuth) : CurrentUserIdProvider {
  override fun currentUserId(): String? = auth.currentUser?.uid
}
