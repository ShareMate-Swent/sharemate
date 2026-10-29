// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlinx.coroutines.tasks.await

/** Firestore-backed implementation of [HouseholdRepository]. */
class HouseholdRepositoryFirestore(
    private val firestore: FirebaseFirestore,
    private val inviteCodeGenerator: InviteCodeGenerator = InviteCodeGenerator()
) : HouseholdRepository {

  override suspend fun createHousehold(
      name: String,
      creatorId: String,
      type: FridgeType
  ): Household {
    require(name.isNotBlank()) { "Household name must not be blank" }
    require(creatorId.isNotBlank()) { "Creator ID must not be blank" }

    repeat(MAX_INVITE_CODE_ATTEMPTS) {
      val inviteCode = if (type == FridgeType.SHARED) inviteCodeGenerator.generate() else ""
      if (type == FridgeType.SHARED) {
        val existing =
            try {
              firestore
                  .collection(FirestoreCollections.INVITE_CODES)
                  .document(inviteCode)
                  .get()
                  .await()
            } catch (exception: FirebaseFirestoreException) {
              // Legacy private invite codes are hidden from other users; retry unavailable codes.
              if (exception.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return@repeat
              throw exception
            }
        if (existing.exists()) return@repeat
      }

      val householdReference = firestore.collection(FirestoreCollections.HOUSEHOLDS).document()
      val household =
          Household(
              id = householdReference.id,
              name = name,
              inviteCode = inviteCode,
              memberIds = listOf(creatorId),
              createdBy = creatorId,
              createdAt = Date(),
              type = type)
      val batch = firestore.batch().set(householdReference, household)
      if (type == FridgeType.SHARED) {
        val inviteCodeReference =
            firestore.collection(FirestoreCollections.INVITE_CODES).document(inviteCode)
        batch.set(inviteCodeReference, mapOf(HOUSEHOLD_ID_FIELD to household.id))
      }

      batch
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

  override suspend fun joinHousehold(inviteCode: String, userId: String): Household {
    require(inviteCode.isNotBlank()) { "Invite code must not be blank" }
    require(userId.isNotBlank()) { "User ID must not be blank" }

    val normalizedCode = inviteCode.trim().uppercase()
    val inviteCodeSnapshot =
        firestore
            .collection(FirestoreCollections.INVITE_CODES)
            .document(normalizedCode)
            .get()
            .await()
    if (!inviteCodeSnapshot.exists()) {
      throw IllegalArgumentException("Invalid invite code")
    }
    val householdId =
        inviteCodeSnapshot.getString(HOUSEHOLD_ID_FIELD)
            ?: throw IllegalArgumentException("Invalid invite code")
    val householdReference =
        firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId)

    firestore
        .batch()
        .update(householdReference, MEMBER_IDS_FIELD, FieldValue.arrayUnion(userId))
        .set(
            firestore.collection(FirestoreCollections.USERS).document(userId),
            mapOf(HOUSEHOLD_ID_FIELD to householdId),
            SetOptions.merge())
        .commit()
        .await()

    return householdReference.get().await().toObject(Household::class.java)
        ?: throw IllegalStateException("Household does not exist for invite code")
  }

  override suspend fun leaveHousehold(userId: String) {
    require(userId.isNotBlank()) { "User ID must not be blank" }

    val memberships =
        firestore
            .collection(FirestoreCollections.HOUSEHOLDS)
            .whereArrayContains(MEMBER_IDS_FIELD, userId)
            .get()
            .await()
    if (memberships.documents.any { it.getString("type") == FridgeType.PRIVATE.name }) {
      throw PrivateHouseholdLeaveException()
    }
    val userReference = firestore.collection(FirestoreCollections.USERS).document(userId)
    // memberIds is the source of truth, but a profile can still point at a household that no
    // longer lists the user; clear that copy too so both sides agree after leaving.
    val hasStaleProfileLink = userReference.get().await().contains(HOUSEHOLD_ID_FIELD)
    if (memberships.isEmpty && !hasStaleProfileLink) return

    val batch = firestore.batch()
    memberships.documents.forEach {
      batch.update(it.reference, MEMBER_IDS_FIELD, FieldValue.arrayRemove(userId))
    }
    batch
        .set(userReference, mapOf(HOUSEHOLD_ID_FIELD to FieldValue.delete()), SetOptions.merge())
        .commit()
        .await()
  }

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
