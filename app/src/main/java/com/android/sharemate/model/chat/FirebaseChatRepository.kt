// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.chat

import android.util.Log
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.android.sharemate.model.incident.IncidentPhotoReference
import com.android.sharemate.model.incident.IncidentPhotoRepository
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

/**
 * Firestore-backed chat. [incidentPhotos] is used to check that an incident's photo was really
 * uploaded before the incident is posted, since Firestore rules cannot read Storage.
 */
class FirebaseChatRepository(
    private val firestore: FirebaseFirestore,
    private val incidentPhotos: IncidentPhotoRepository,
) : ChatRepository {

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
    return post(
        householdId, senderId, senderName, mapOf(MessageFields.TEXT to Message.normalizeText(text)))
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
        type !in ACTIVITY_TYPES ||
        itemName.isBlank())
        return null
    return post(
        householdId,
        senderId,
        senderName,
        mapOf(
            MessageFields.TEXT to "",
            MessageFields.TYPE to type.name,
            MessageFields.ITEM_NAME to itemName.trim(),
            MessageFields.ITEM_IMAGE_URL to itemImageUrl?.takeIf { it.isNotBlank() }),
        documentId =
            stableActivityId(
                householdId,
                senderId,
                type,
                itemName.trim(),
                itemImageUrl?.takeIf { it.isNotBlank() }))
  }

  private fun stableActivityId(
      householdId: String,
      senderId: String,
      type: MessageType,
      itemName: String,
      itemImageUrl: String?,
  ): String {
    val source =
        listOf(householdId, senderId, type.name, itemName, itemImageUrl.orEmpty())
            .joinToString(SEPARATOR)
    val digest =
        MessageDigest.getInstance(HASH_ALGORITHM).digest(source.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
  }

  override suspend fun sendIncident(
      householdId: String,
      senderId: String,
      senderName: String,
      description: String,
      photo: IncidentPhotoReference
  ): String? {
    if (householdId.isBlank() ||
        senderId.isBlank() ||
        !Message.isValidText(description) ||
        photo.householdId != householdId ||
        photo.authorId != senderId)
        return null
    // Never post a message whose photo can never load; a failed check also refuses.
    if (incidentPhotos.exists(photo).getOrNull() != true) return null
    return post(
        householdId,
        senderId,
        senderName,
        mapOf(
            MessageFields.TEXT to Message.normalizeText(description),
            MessageFields.TYPE to MessageType.INCIDENT.name,
            MessageFields.INCIDENT_PHOTO_PATH to photo.path))
  }

  /**
   * Writes a new message made of the common fields plus the type-specific [fields]; returns its id
   * after acknowledgement, or null on failure. Cancellation is rethrown.
   */
  private suspend fun post(
      householdId: String,
      senderId: String,
      senderName: String,
      fields: Map<String, Any?>,
      documentId: String? = null,
  ): String? =
      try {
        val document =
            documentId?.let { messages(householdId).document(it) }
                ?: messages(householdId).document()
        writeMessage(document, messageData(document.id, senderId, senderName) + fields, senderId)
        document.id
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
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
    val ACTIVITY_TYPES = setOf(MessageType.EATEN, MessageType.THROWN)
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
      val incidentPhoto = if (type == MessageType.INCIDENT) incidentPhotoOrNull() else null
      val hasTypeFields =
          when (type) {
            MessageType.TEXT -> true
            MessageType.EATEN,
            MessageType.THROWN -> itemName.isNotBlank()
            MessageType.INCIDENT -> !text.isNullOrBlank() && incidentPhoto != null
          }
      if (senderId == null || text == null || !hasTypeFields) {
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
            itemImageUrl = getString(MessageFields.ITEM_IMAGE_URL)?.takeIf { it.isNotBlank() },
            incidentPhoto = incidentPhoto)
      }
    } catch (_: RuntimeException) {
      // Wrongly typed fields make the Firestore getters throw; skip the malformed document.
      null
    }

/** The stored incident photo reference, or null when it is missing or not a valid path. */
private fun DocumentSnapshot.incidentPhotoOrNull(): IncidentPhotoReference? =
    getString(MessageFields.INCIDENT_PHOTO_PATH)?.let { path ->
      try {
        IncidentPhotoReference(path)
      } catch (_: IllegalArgumentException) {
        null
      }
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
  const val INCIDENT_PHOTO_PATH = "incidentPhotoPath"
}
