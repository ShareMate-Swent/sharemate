// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdFields
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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

/** Server-confirmed names, using one query per ten members rather than per card. */
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
                  household(householdId).addSnapshotListener(MetadataChanges.INCLUDE) {
                      snapshot,
                      error ->
                    if (error != null) close(error)
                    else {
                      val ids =
                          (snapshot?.get(HouseholdFields.MEMBER_IDS) as? List<*>)
                              ?.filterIsInstance<String>()
                              .orEmpty()
                      val privateFridge =
                          snapshot?.getString(HouseholdFields.TYPE) == FridgeType.PRIVATE.name
                      trySend(
                          if (snapshot != null &&
                              !snapshot.metadata.isFromCache &&
                              !snapshot.metadata.hasPendingWrites() &&
                              viewerId in ids &&
                              (!privateFridge ||
                                  snapshot.getString(HouseholdFields.CREATED_BY) == viewerId))
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
              combine(
                      ids.chunked(MEMBER_QUERY_BATCH_SIZE).map { batch ->
                        observeBatch(householdId, batch)
                      }) { results ->
                        results.fold(emptyMap<String, String>()) { names, result -> names + result }
                      }
                  .onStart { emit(emptyMap()) }
        }
        .distinctUntilChanged()
        .onStart { emit(emptyMap()) }
  }

  private fun observeBatch(householdId: String, ids: List<String>): Flow<Map<String, String>> =
      callbackFlow {
        val registration =
            household(householdId)
                .collection(FirestoreCollections.MEMBER_NAMES)
                .whereIn(FieldPath.documentId(), ids)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                  if (error != null) close(error)
                  else if (snapshot == null ||
                      snapshot.metadata.isFromCache ||
                      snapshot.metadata.hasPendingWrites()) {
                    trySend(emptyMap())
                  } else
                      trySend(
                          snapshot.documents
                              .orEmpty()
                              .mapNotNull { document ->
                                ownerDisplayName(
                                        document.get(UserProfileFields.DISPLAY_NAME) as? String)
                                    ?.let { document.id to it }
                              }
                              .toMap())
                }
        awaitClose { registration.remove() }
      }

  override suspend fun publishOwnName(householdId: String, userId: String, authName: String?) {
    queueOwnName(householdId, userId, authName)
  }

  override suspend fun queueOwnName(
      householdId: String,
      userId: String,
      authName: String?
  ): Deferred<Result<Unit>> {
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
                    .get(UserProfileFields.DISPLAY_NAME) as? String)
    val reference =
        household(householdId).collection(FirestoreCollections.MEMBER_NAMES).document(userId)
    val task =
        if (name == null) reference.delete()
        else reference.set(mapOf(UserProfileFields.DISPLAY_NAME to name))
    if (task.isComplete && !task.isSuccessful)
        throw task.exception ?: IllegalStateException("Write cancelled")
    val confirmation = CompletableDeferred<Result<Unit>>()
    task.addOnCompleteListener(Executor { it.run() }) { completed ->
      confirmation.complete(
          if (completed.isSuccessful) Result.success(Unit)
          else Result.failure(completed.exception ?: IllegalStateException("Write cancelled")))
    }
    return confirmation
  }
}

private const val MEMBER_QUERY_BATCH_SIZE = 10
