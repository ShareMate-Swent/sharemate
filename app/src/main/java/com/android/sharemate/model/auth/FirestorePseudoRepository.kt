// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/** Reserves normalized pseudo names atomically in Firestore without exposing the claim index. */
class FirestorePseudoRepository(private val firestore: FirebaseFirestore) : PseudoRepository {
  override suspend fun claim(uid: String, pseudo: String) {
    require(uid.isNotBlank()) { USER_ID_REQUIRED_MESSAGE }
    require(normalizePseudo(pseudo).isNotEmpty()) { PSEUDO_REQUIRED_MESSAGE }

    val claim =
        firestore.collection(FirestoreCollections.PSEUDO_CLAIMS).document(pseudoClaimId(pseudo))
    try {
      firestore
          .runTransaction { transaction ->
            val existing = transaction.get(claim)
            when {
              !existing.exists() -> {
                transaction.set(claim, mapOf(UID_FIELD to uid))
                Unit
              }
              existing.getString(UID_FIELD) == uid -> Unit
              uid in (existing.get(LEGACY_UIDS_FIELD) as? List<*>).orEmpty() -> Unit
              else -> throw AuthException(AuthError.PSEUDO_TAKEN)
            }
          }
          .await()
    } catch (failure: AuthException) {
      throw failure
    } catch (failure: FirebaseFirestoreException) {
      if (failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        throw AuthException(AuthError.PSEUDO_TAKEN)
      }
      throw failure
    }
  }

  override suspend fun release(uid: String, pseudo: String) {
    val claim =
        firestore.collection(FirestoreCollections.PSEUDO_CLAIMS).document(pseudoClaimId(pseudo))
    val profile = firestore.collection(FirestoreCollections.PROFILES).document(uid)
    firestore
        .runTransaction { transaction ->
          val existing = transaction.get(claim)
          val currentPseudo = transaction.get(profile).getString(DISPLAY_NAME_FIELD)
          val profileStillUsesPseudo =
              currentPseudo != null && normalizePseudo(currentPseudo) == normalizePseudo(pseudo)
          if (existing.getString(UID_FIELD) == uid && !profileStillUsesPseudo) {
            transaction.delete(claim)
          }
        }
        .await()
  }

  private companion object {
    const val DISPLAY_NAME_FIELD = "displayName"
    const val UID_FIELD = "uid"
    const val LEGACY_UIDS_FIELD = "legacyUids"
    const val PSEUDO_REQUIRED_MESSAGE = "A pseudo claim needs a non-blank pseudo"
    const val USER_ID_REQUIRED_MESSAGE = "A pseudo claim needs a user id"
  }
}
