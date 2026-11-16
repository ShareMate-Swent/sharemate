// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.PushTokenFields
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Keeps push tokens in a `pushTokens` sub-collection of the owning user's document. */
class FirebasePushTokenRepository(private val firestore: FirebaseFirestore) : PushTokenRepository {

  override suspend fun saveToken(userId: String, deviceId: String, token: String) {
    if (userId.isBlank() || deviceId.isBlank() || token.isBlank()) return
    val data =
        mapOf(
            PushTokenFields.TOKEN to token,
            PushTokenFields.PLATFORM to PushTokenFields.PLATFORM_ANDROID,
            PushTokenFields.UPDATED_AT to FieldValue.serverTimestamp())
    document(userId, deviceId).set(data).await()
  }

  private fun document(userId: String, deviceId: String) =
      firestore
          .collection(FirestoreCollections.USERS)
          .document(userId)
          .collection(FirestoreCollections.PUSH_TOKENS)
          .document(deviceId)
}
