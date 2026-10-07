// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlinx.coroutines.tasks.await

/** Firestore-backed implementation of [HouseholdRepository]. */
class HouseholdRepositoryFirestore(
    private val firestore: FirebaseFirestore,
    private val inviteCodeGenerator: InviteCodeGenerator = InviteCodeGenerator()
) : HouseholdRepository {

  override suspend fun createHousehold(name: String, creatorId: String): Household {
    require(name.isNotBlank()) { "Household name must not be blank" }
    require(creatorId.isNotBlank()) { "Creator ID must not be blank" }

    repeat(MAX_INVITE_CODE_ATTEMPTS) {
      val inviteCode = inviteCodeGenerator.generate()
      val existing =
          firestore
              .collection(FirestoreCollections.HOUSEHOLDS)
              .whereEqualTo(INVITE_CODE_FIELD, inviteCode)
              .limit(1)
              .get()
              .await()
      if (!existing.isEmpty) return@repeat

      val householdReference = firestore.collection(FirestoreCollections.HOUSEHOLDS).document()
      val household =
          Household(
              id = householdReference.id,
              name = name,
              inviteCode = inviteCode,
              memberIds = listOf(creatorId),
              createdBy = creatorId,
              createdAt = Date())

      firestore
          .batch()
          .set(householdReference, household)
          .set(
              firestore.collection(FirestoreCollections.USERS).document(creatorId),
              mapOf(HOUSEHOLD_ID_FIELD to household.id),
              SetOptions.merge())
          .commit()
          .await()
      return household
    }

    throw IllegalStateException("Could not generate a unique household invite code")
  }

  override suspend fun joinHousehold(inviteCode: String, userId: String): Household =
      throw UnsupportedOperationException("Joining a household is implemented in Task 2.4")

  override suspend fun getHousehold(householdId: String): Household? {
    val snapshot =
        firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId).get().await()
    return if (snapshot.exists()) snapshot.toObject(Household::class.java) else null
  }

  override suspend fun getHouseholdForUser(userId: String): Household? {
    val snapshot =
        firestore
            .collection(FirestoreCollections.HOUSEHOLDS)
            .whereArrayContains(MEMBER_IDS_FIELD, userId)
            .limit(1)
            .get()
            .await()
    return snapshot.documents.firstOrNull()?.toObject(Household::class.java)
  }

  private companion object {
    const val HOUSEHOLD_ID_FIELD = "householdId"
    const val INVITE_CODE_FIELD = "inviteCode"
    const val MEMBER_IDS_FIELD = "memberIds"
    const val MAX_INVITE_CODE_ATTEMPTS = 10
  }
}
