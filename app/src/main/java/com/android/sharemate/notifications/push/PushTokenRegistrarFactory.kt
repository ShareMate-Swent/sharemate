// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.push.FirebasePushTokenRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await

/** Wires a production [PushTokenRegistrar] from the Firebase services it is given. */
object PushTokenRegistrarFactory {
  /**
   * Shared by every registrar built here, so they all order their operations against each other.
   */
  private val operations = Mutex()

  /**
   * Builds a registrar for the device identified by [deviceId]. [messaging] is only resolved when a
   * token must be read, so building the registrar never touches FCM.
   */
  fun create(
      deviceId: String,
      auth: FirebaseAuth,
      firestore: FirebaseFirestore,
      messaging: Lazy<FirebaseMessaging>,
  ): PushTokenRegistrar =
      PushTokenRegistrar(
          FirebaseCurrentUserIdProvider(auth),
          FirebasePushTokenRepository(firestore),
          deviceId,
          operations,
          fetchToken = { messaging.value.token.await() })
}
