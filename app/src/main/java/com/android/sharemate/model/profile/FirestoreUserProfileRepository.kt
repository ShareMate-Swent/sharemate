// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.profile

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.android.sharemate.model.household.HouseholdFields
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await

/**
 * Stores public profiles in the `profiles` collection, one document per user.
 *
 * Members are read from the household's `memberIds`, the source of truth for membership, and each
 * profile is then fetched on its own: security rules allow a member to get another member's
 * profile, but not to list profiles.
 */
class FirestoreUserProfileRepository(private val firestore: FirebaseFirestore) :
    UserProfileRepository {

  private val profiles
    get() = firestore.collection(FirestoreCollections.PROFILES)

  override suspend fun savePublicProfile(profile: UserProfile) {
    require(profile.uid.isNotBlank()) { "A profile needs a user id" }
    val document = profiles.document(profile.uid)
    firestore
        .runTransaction { transaction ->
          val existingName = transaction.get(document).getString(UserProfileFields.DISPLAY_NAME)
          val refreshed = profile.withExistingDisplayName(existingName)
          transaction.set(
              document,
              mapOf(
                  UserProfileFields.DISPLAY_NAME to refreshed.displayName,
                  UserProfileFields.PHOTO_URL to refreshed.photoUrl,
                  UserProfileFields.EMAIL to refreshed.email,
              ))
        }
        .await()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeMembers(householdId: String): Flow<List<UserProfile>> {
    require(householdId.isNotBlank()) { "A household id is required" }
    return observeMemberIds(householdId).distinctUntilChanged().mapLatest { ids ->
      coroutineScope { ids.map { id -> async { readProfile(id) } }.awaitAll() }
    }
  }

  private fun observeMemberIds(householdId: String): Flow<List<String>> = callbackFlow {
    val registration =
        firestore
            .collection(FirestoreCollections.HOUSEHOLDS)
            .document(householdId)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
                return@addSnapshotListener
              }
              trySend(snapshot?.memberIds().orEmpty())
            }
    awaitClose { registration.remove() }
  }

  private suspend fun readProfile(uid: String): UserProfile {
    val document = profiles.document(uid).get().await()
    return UserProfile(
        uid = uid,
        displayName = document.text(UserProfileFields.DISPLAY_NAME),
        photoUrl = document.text(UserProfileFields.PHOTO_URL),
        email = document.text(UserProfileFields.EMAIL),
    )
  }

  private fun DocumentSnapshot.memberIds(): List<String> =
      (get(HouseholdFields.MEMBER_IDS) as? List<*>)?.filterIsInstance<String>().orEmpty()

  private fun DocumentSnapshot.text(field: String): String? =
      getString(field)?.takeIf { it.isNotBlank() }
}
