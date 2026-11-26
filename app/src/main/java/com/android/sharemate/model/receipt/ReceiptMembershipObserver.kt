// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Server-only membership snapshots also cover departure or deletion on another device. */
class ReceiptMembershipObserver(private val db: FirebaseFirestore) {
  fun observe(userId: String): Flow<Set<String>> = callbackFlow {
    val registration =
        db.collection("households").whereArrayContains("memberIds", userId).addSnapshotListener(
            MetadataChanges.INCLUDE) { snapshot, error ->
              if (error != null) close(error)
              else if (snapshot != null &&
                  !snapshot.metadata.isFromCache &&
                  !snapshot.metadata.hasPendingWrites())
                  trySend(snapshot.documents.map { it.id }.toSet())
            }
    awaitClose { registration.remove() }
  }
}
