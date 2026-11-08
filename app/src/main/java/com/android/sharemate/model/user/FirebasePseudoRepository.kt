// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import android.util.Log
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserFields
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

/** Stores the pseudo in the private profile and a household-readable pseudo-only document. */
class FirebasePseudoRepository(private val firestore: FirebaseFirestore) : PseudoRepository {

  private fun document(userId: String) =
      firestore.collection(FirestoreCollections.USERS).document(userId)

  private fun sharedDocument(userId: String) =
      firestore.collection(FirestoreCollections.USER_PSEUDOS).document(userId)

  override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> {
    val ids = userIds.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return flowOf(emptyMap())
    return combine(ids.map { id -> observeOne(id) }) { pseudos ->
          ids.zip(pseudos).mapNotNull { (id, pseudo) -> pseudo?.let { id to it } }.toMap()
        }
        .distinctUntilChanged()
  }

  private fun observeOne(userId: String): Flow<String?> = callbackFlow {
    trySend(null)
    val registration =
        sharedDocument(userId).addSnapshotListener { snapshot, error ->
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
    val data = mapOf(UserFields.PSEUDO to value)
    firestore
        .batch()
        .set(document(userId), data, SetOptions.merge())
        .set(sharedDocument(userId), data)
        .commit()
        .await()
  }

  private fun DocumentSnapshot.toPseudo(): String? =
      (get(UserFields.PSEUDO) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
