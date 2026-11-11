// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.finance.FinanceFields.HOUSEHOLD_ID
import com.android.sharemate.model.finance.FinanceFields.ID
import com.android.sharemate.model.finance.FinanceFields.PAID_BY
import com.android.sharemate.model.finance.FinanceFields.SHARES
import com.android.sharemate.model.finance.FinanceFields.TOTAL_AMOUNT_CENTS
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reads `households/{householdId}/expenses/{expenseId}`. Each document has exactly `id` (matching
 * expenseId), `householdId` (matching its path), `paidBy` (user ID), `totalAmountCents` (Firestore
 * integer) and `shares` (user-ID-to-integer map). Amounts follow [FinanceAmounts]' CHF policy and
 * shares are already allocated; this reader neither rounds nor infers an equal split.
 *
 * Expenses must remain read-only through the injected Firestore instance, including attempted
 * deletions. Filtering SDK-reported cached/pending snapshots does not certify a server-only view
 * when other code submits local writes; pending deletions may not be reflected in query metadata.
 * Any future producer integration must revisit this optimistic-write limitation.
 */
class FirestoreHouseholdExpenseRepository(private val firestore: FirebaseFirestore) :
    HouseholdExpenseRepository {
  override fun observeHouseholdExpenses(householdId: String): Flow<List<SharedExpense>> {
    require(householdId.isNotBlank() && '/' !in householdId) {
      "A household document ID is required"
    }
    return callbackFlow {
          val household =
              firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId)
          // Watch parent access directly: an unchanged or empty expense query may not receive
          // another snapshot when membership is revoked.
          val membershipRegistration =
              household.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) {
                  close(failure)
                } else if (snapshot == null) {
                  close(IllegalStateException("Missing household snapshot"))
                } else {
                  try {
                    snapshot.requireSharedHousehold()
                  } catch (error: FirebaseFirestoreException) {
                    close(error)
                  }
                }
              }
          try {
            val registration =
                household.collection(FirestoreCollections.EXPENSES).addSnapshotListener(
                    MetadataChanges.INCLUDE) { snapshot, failure ->
                      if (failure != null) {
                        close(failure)
                        return@addSnapshotListener
                      }
                      if (snapshot == null) {
                        close(IllegalStateException("Missing expense query snapshot"))
                        return@addSnapshotListener
                      }
                      if (!snapshot.metadata.isServerConfirmed()) {
                        return@addSnapshotListener
                      }
                      try {
                        val expenses =
                            Collections.unmodifiableList(
                                snapshot.documents.map { it.toSharedExpense(householdId) })
                        trySend(expenses)
                      } catch (exception: Exception) {
                        close(exception)
                      }
                    }
            awaitClose { registration.remove() }
          } finally {
            membershipRegistration.remove()
          }
        }
        .buffer(Channel.CONFLATED)
        .distinctUntilChanged()
  }
}

/** Strict mapping: corrupted financial data must fail the whole observation, never disappear. */
internal fun DocumentSnapshot.toSharedExpense(householdId: String): SharedExpense {
  val fields = requireNotNull(data) { "Missing expense document data" }
  require(fields.keys == setOf(ID, HOUSEHOLD_ID, PAID_BY, TOTAL_AMOUNT_CENTS, SHARES)) {
    "Unexpected expense fields"
  }
  require(fields[ID] == id && fields[HOUSEHOLD_ID] == householdId) { "Expense path mismatch" }
  val paidBy = requireNotNull(fields[PAID_BY] as? String) { "Invalid expense payer" }
  val total = requireNotNull(fields[TOTAL_AMOUNT_CENTS] as? Long) { "Invalid expense amount" }
  val rawShares = requireNotNull(fields[SHARES] as? Map<*, *>) { "Invalid expense shares" }
  val shares =
      rawShares.entries.associate { (userId, amount) ->
        require(userId is String && amount is Long) { "Invalid expense share entry" }
        userId to amount
      }
  return SharedExpense(id, householdId, paidBy, total, shares)
}
