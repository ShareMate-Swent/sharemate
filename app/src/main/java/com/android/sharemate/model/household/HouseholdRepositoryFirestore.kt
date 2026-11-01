// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Firestore-backed implementation of [HouseholdRepository]. */
class HouseholdRepositoryFirestore(
    private val firestore: FirebaseFirestore,
    private val inviteCodeGenerator: InviteCodeGenerator = InviteCodeGenerator(),
    private val commitCreation: suspend (WriteBatch) -> Unit = { it.commit().await() }
) : HouseholdRepository {

  override suspend fun createHousehold(
      name: String,
      creatorId: String,
      type: FridgeType,
      operationId: String
  ): Household {
    require(name.isNotBlank()) { "Household name must not be blank" }
    require(creatorId.isNotBlank()) { "Creator ID must not be blank" }
    require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid creation operation ID" }
    val householdReference =
        firestore.collection(FirestoreCollections.HOUSEHOLDS).document("$creatorId:$operationId")

    suspend fun reconcile(): Household? {
      val snapshot = householdReference.get(Source.SERVER).await()
      check(!snapshot.metadata.hasPendingWrites()) { "Creation outcome is still pending" }
      if (!snapshot.exists()) return null
      val previous = checkNotNull(snapshot.toObject(Household::class.java))
      require(previous.createdBy == creatorId && previous.name == name && previous.type == type) {
        "Creation operation does not match the original request"
      }
      return previous
    }
    reconcile()?.let {
      return it
    }

    repeat(MAX_INVITE_CODE_ATTEMPTS) {
      val inviteCode = if (type == FridgeType.SHARED) inviteCodeGenerator.generate() else ""
      if (type == FridgeType.SHARED) {
        val existing =
            try {
              firestore
                  .collection(FirestoreCollections.INVITE_CODES)
                  .document(inviteCode)
                  .get(Source.SERVER)
                  .await()
            } catch (exception: FirebaseFirestoreException) {
              // Legacy private invite codes are hidden from other users; retry unavailable codes.
              if (exception.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return@repeat
              throw exception
            }
        if (existing.exists()) return@repeat
      }

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

      try {
        commitCreation(
            batch.set(
                firestore.collection(FirestoreCollections.USERS).document(creatorId),
                mapOf(HOUSEHOLD_ID_FIELD to household.id),
                SetOptions.merge()))
      } catch (failure: FirebaseFirestoreException) {
        if (failure.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw failure
        // Another caller may have completed this same operation. Reconcile it before deciding
        // whether a shared code was claimed; a denied commit alone is not a collision.
        try {
          reconcile()?.let {
            return it
          }
          if (type != FridgeType.SHARED) throw failure
          val claimed =
              firestore
                  .collection(FirestoreCollections.INVITE_CODES)
                  .document(inviteCode)
                  .get(Source.SERVER)
                  .await()
          check(!claimed.metadata.hasPendingWrites())
          val claimedBy = claimed.getString(HOUSEHOLD_ID_FIELD)
          if (claimedBy.isNullOrBlank() || claimedBy == household.id) throw failure
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          throw failure
        }
        return@repeat
      }
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
