// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import android.util.Log
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

class FirebaseChatRepository(private val firestore: FirebaseFirestore) : ChatRepository {

  private fun messages(householdId: String) =
      firestore
          .collection(FirestoreCollections.HOUSEHOLDS)
          .document(householdId)
          .collection(FirestoreCollections.MESSAGES)

  override fun observeMessages(householdId: String): Flow<List<Message>> {
    if (householdId.isBlank()) return flowOf(emptyList())
    val query = messages(householdId).orderBy(MessageFields.TIMESTAMP, Query.Direction.ASCENDING)
    return callbackFlow {
      val registration =
          query.addSnapshotListener { snapshot, error ->
            if (error != null) {
              close(error)
              return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toMessageOrNull() }.orEmpty())
          }
      awaitClose { registration.remove() }
    }
  }

  override suspend fun sendMessage(
      householdId: String,
      senderId: String,
      senderName: String,
      text: String
  ): String? {
    if (householdId.isBlank() || senderId.isBlank() || !Message.isValidText(text)) return null
    return try {
      val document = messages(householdId).document()
      val message =
          messageData(document.id, senderId, senderName) +
              mapOf(MessageFields.TEXT to Message.normalizeText(text))
      writeMessage(document, message, senderId)
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  override suspend fun sendActivity(
      householdId: String,
      senderId: String,
      senderName: String,
      type: MessageType,
      itemName: String,
      itemImageUrl: String?
  ): String? {
    if (householdId.isBlank() ||
        senderId.isBlank() ||
        type == MessageType.TEXT ||
        itemName.isBlank())
        return null
    return try {
      val document =
          messages(householdId)
              .document(stableActivityId(householdId, senderId, type, itemName.trim()))
      val message =
          messageData(document.id, senderId, senderName) +
              mapOf(
                  MessageFields.TEXT to "",
                  MessageFields.TYPE to type.name,
                  MessageFields.ITEM_NAME to itemName.trim(),
                  MessageFields.ITEM_IMAGE_URL to itemImageUrl?.takeIf { it.isNotBlank() })
      writeMessage(document, message, senderId)
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  private fun stableActivityId(
      householdId: String,
      senderId: String,
      type: MessageType,
      itemName: String,
  ): String {
    val source = listOf(householdId, senderId, type.name, itemName).joinToString(SEPARATOR)
    val digest =
        MessageDigest.getInstance(HASH_ALGORITHM).digest(source.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
  }

  private suspend fun messageData(
      id: String,
      senderId: String,
      senderName: String
  ): Map<String, Any> {
    val name =
        try {
          val profile =
              firestore
                  .collection(FirestoreCollections.PROFILES)
                  .document(senderId)
                  .get(Source.CACHE)
                  .await()
          if (profile.exists()) profile.getString(UserProfileFields.DISPLAY_NAME) else null
        } catch (error: FirebaseFirestoreException) {
          if (error.code == FirebaseFirestoreException.Code.UNAVAILABLE) senderName else throw error
        }
    return mapOf(
        MessageFields.ID to id,
        MessageFields.SENDER_ID to senderId,
        MessageFields.SENDER_NAME to safeSenderName(name),
        MessageFields.TIMESTAMP to FieldValue.serverTimestamp())
  }

  private suspend fun writeMessage(
      document: DocumentReference,
      message: Map<String, Any?>,
      senderId: String
  ) {
    try {
      document.set(message).await()
    } catch (error: FirebaseFirestoreException) {
      if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        try {
          firestore
              .collection(FirestoreCollections.PROFILES)
              .document(senderId)
              .get(Source.SERVER)
              .addOnFailureListener(Executor { it.run() }) { failure ->
                Log.w(TAG, "Unable to refresh the chat sender profile", failure)
              }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: RuntimeException) {
          Log.w(TAG, "Unable to refresh the chat sender profile", failure)
        }
      }
      throw error
    }
  }

  private companion object {
    const val TAG = "FirebaseChatRepository"
    const val HASH_ALGORITHM = "SHA-256"
    const val SEPARATOR = "|"
  }
}

internal const val DEFAULT_CHAT_SENDER_NAME = "Member"

private val visibleCharacter = Regex("\\S")

internal fun safeSenderName(name: String?): String =
    name?.takeIf { visibleCharacter.containsMatchIn(it) && '@' !in it } ?: DEFAULT_CHAT_SENDER_NAME

internal fun DocumentSnapshot.toMessageOrNull(): Message? =
    try {
      val senderId = getString(MessageFields.SENDER_ID)?.takeIf { it.isNotBlank() }
      val type = MessageType.fromStored(getString(MessageFields.TYPE))
      val text = getString(MessageFields.TEXT) ?: if (type == MessageType.TEXT) null else ""
      val itemName = getString(MessageFields.ITEM_NAME).orEmpty()
      if (senderId == null || text == null || (type != MessageType.TEXT && itemName.isBlank())) {
        null
      } else {
        Message(
            id = id,
            senderId = senderId,
            senderName = safeSenderName(getString(MessageFields.SENDER_NAME)),
            text = text,
            timestamp =
                getTimestamp(
                        MessageFields.TIMESTAMP, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?.toDate(),
            type = type,
            itemName = itemName,
            itemImageUrl = getString(MessageFields.ITEM_IMAGE_URL)?.takeIf { it.isNotBlank() })
      }
    } catch (_: RuntimeException) {
      // Wrongly typed fields make the Firestore getters throw; skip the malformed document.
      null
    }

/** Fields of a chat message document in Firestore. */
private object MessageFields {
  const val ID = "id"
  const val SENDER_ID = "senderId"
  const val SENDER_NAME = "senderName"
  const val TIMESTAMP = "timestamp"
  const val TYPE = "type"
  const val TEXT = "text"
  const val ITEM_NAME = "itemName"
  const val ITEM_IMAGE_URL = "itemImageUrl"
}
