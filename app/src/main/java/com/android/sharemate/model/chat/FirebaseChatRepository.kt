// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Date
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
    val query = messages(householdId).orderBy("timestamp", Query.Direction.ASCENDING)
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
          Message(
              id = document.id,
              senderId = senderId,
              senderName = senderName,
              text = Message.normalizeText(text),
              timestamp = Date())
      document.set(message).await()
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }
}

internal fun DocumentSnapshot.toMessageOrNull(): Message? =
    try {
      val senderId = getString("senderId")?.takeIf { it.isNotBlank() }
      val text = getString("text")
      if (senderId == null || text == null) {
        null
      } else {
        Message(
            id = id,
            senderId = senderId,
            senderName = getString("senderName").orEmpty(),
            text = text,
            timestamp = getTimestamp("timestamp")?.toDate())
      }
    } catch (_: RuntimeException) {
      // Wrongly typed fields make the Firestore getters throw; skip the malformed document.
      null
    }
