// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await

/** One membership listener and one cached query per ten members, independent of card count. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseMemberNameRepository(private val firestore: FirebaseFirestore) :
    MemberNameRepository {
  private fun household(id: String) =
      firestore.collection(FirestoreCollections.HOUSEHOLDS).document(id)

  override fun observeNames(householdId: String, viewerId: String): Flow<Map<String, String>> {
    require(householdId.isNotBlank() && '/' !in householdId)
    require(viewerId.isNotBlank() && '/' !in viewerId)
    val members =
        callbackFlow<List<String>> {
              val registration =
                  household(householdId).addSnapshotListener { snapshot, error ->
                    if (error != null) close(error)
                    else {
                      val ids =
                          (snapshot?.get("memberIds") as? List<*>)
                              ?.filterIsInstance<String>()
                              .orEmpty()
                      val privateFridge = snapshot?.getString("type") == "PRIVATE"
                      trySend(
                          if (viewerId in ids &&
                              (!privateFridge || snapshot?.getString("createdBy") == viewerId))
                              ids.filter { it.isNotBlank() && '/' !in it }.distinct().sorted()
                          else emptyList())
                    }
                    Unit
                  }
              awaitClose { registration.remove() }
            }
            .distinctUntilChanged()
    return members
        .flatMapLatest { ids ->
          if (ids.isEmpty()) flowOf(emptyMap())
          else
              combine(ids.chunked(10).map { batch -> observeBatch(householdId, batch) }) { results
                    ->
                    results.fold(emptyMap<String, String>()) { names, result -> names + result }
                  }
                  .onStart { emit(emptyMap()) }
        }
        .distinctUntilChanged()
  }

  private fun observeBatch(householdId: String, ids: List<String>): Flow<Map<String, String>> =
      callbackFlow {
        val registration =
            household(householdId)
                .collection(FirestoreCollections.MEMBER_NAMES)
                .whereIn(FieldPath.documentId(), ids)
                .addSnapshotListener { snapshot, error ->
                  if (error != null) close(error)
                  else
                      trySend(
                          snapshot
                              ?.documents
                              .orEmpty()
                              .mapNotNull { document ->
                                ownerDisplayName(document.get("displayName") as? String)?.let {
                                  document.id to it
                                }
                              }
                              .toMap())
                }
        awaitClose { registration.remove() }
      }

  override suspend fun publishOwnName(householdId: String, userId: String, authName: String?) {
    require(householdId.isNotBlank() && '/' !in householdId)
    require(userId.isNotBlank() && '/' !in userId)
    val name =
        ownerDisplayName(authName)
            ?: ownerDisplayName(
                firestore
                    .collection(FirestoreCollections.USERS)
                    .document(userId)
                    .get()
                    .await()
                    .get("displayName") as? String)
    val reference =
        household(householdId).collection(FirestoreCollections.MEMBER_NAMES).document(userId)
    if (name == null) reference.delete().await()
    else reference.set(mapOf("displayName" to name)).await()
  }
}
