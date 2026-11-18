// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.PushTokenFields
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Keeps push tokens in a `pushTokens` sub-collection of the owning user's document. */
class FirebasePushTokenRepository(private val firestore: FirebaseFirestore) : PushTokenRepository {

  override suspend fun saveToken(userId: String, deviceId: String, token: String) {
    require(token.isNotBlank() && token.length <= PushTokenFields.MAX_TOKEN_LENGTH) {
      INVALID_TOKEN_MESSAGE
    }
    val data =
        mapOf(
            PushTokenFields.TOKEN to token,
            PushTokenFields.PLATFORM to PushTokenFields.PLATFORM_ANDROID,
            PushTokenFields.UPDATED_AT to FieldValue.serverTimestamp())
    document(userId, deviceId).set(data).await()
  }

  override suspend fun deleteToken(userId: String, deviceId: String) {
    document(userId, deviceId).delete().await()
  }

  private fun document(userId: String, deviceId: String): DocumentReference {
    require(userId.isNotBlank() && deviceId.isNotBlank()) { INVALID_IDENTIFIER_MESSAGE }
    return firestore
        .collection(FirestoreCollections.USERS)
        .document(userId)
        .collection(FirestoreCollections.PUSH_TOKENS)
        .document(deviceId)
  }

  private companion object {
    const val INVALID_TOKEN_MESSAGE = "The push token is blank or too long"
    const val INVALID_IDENTIFIER_MESSAGE = "The user id and the device id must not be blank"
  }
}
