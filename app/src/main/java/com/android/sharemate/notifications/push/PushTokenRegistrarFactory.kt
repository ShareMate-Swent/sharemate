// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.auth.FirestorePseudoRepository
import com.android.sharemate.model.push.FirebasePushTokenRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/** Wires a production [PushTokenRegistrar] for callers that have no other repositories at hand. */
object PushTokenRegistrarFactory {
  fun create(context: Context): PushTokenRegistrar {
    val firestore = FirebaseFirestore.getInstance()
    val authRepository =
        FirebaseAuthRepository(FirebaseAuth.getInstance(), FirestorePseudoRepository(firestore))
    return PushTokenRegistrar(
        authRepository, FirebasePushTokenRepository(firestore), DeviceId.getOrCreate(context))
  }
}
