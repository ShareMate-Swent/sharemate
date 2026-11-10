// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import android.util.Log
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** Stores the pseudo as the display name in the household-readable profile. */
class FirebasePseudoRepository(private val firestore: FirebaseFirestore) : PseudoRepository {

  private fun document(userId: String) =
      firestore.collection(FirestoreCollections.PROFILES).document(userId)

  override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> {
    val ids = userIds.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return flowOf(emptyMap())
    return combine(ids.map { id -> observeOne(id) }) { pseudos ->
          ids.zip(pseudos).mapNotNull { (id, pseudo) -> pseudo?.let { id to it } }.toMap()
        }
        .distinctUntilChanged()
  }

  private fun observeOne(userId: String): Flow<String?> = callbackFlow {
    val registration =
        document(userId).addSnapshotListener { snapshot, error ->
          if (error != null) {
            Log.w("PseudoRepository", "Unable to observe an owner pseudo", error)
            trySend(null)
          } else {
            trySend(snapshot?.toPseudo())
          }
        }
    awaitClose { registration.remove() }
  }

  override suspend fun getPseudo(userId: String): String? =
      document(userId).get().await().toPseudo()

  override suspend fun setPseudo(userId: String, pseudo: String) {
    val value = pseudo.trim()
    require(value.isNotEmpty()) { "A pseudo must not be blank" }
    require(value.length <= MAX_PSEUDO_LENGTH) { "A pseudo must not exceed $MAX_PSEUDO_LENGTH" }
    document(userId).set(mapOf(UserProfileFields.DISPLAY_NAME to value), SetOptions.merge()).await()
  }

  private fun DocumentSnapshot.toPseudo(): String? =
      (get(UserProfileFields.DISPLAY_NAME) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
