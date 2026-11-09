// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.FridgeType
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/**
 * Publishes server-backed ledger snapshots only after all affected Firestore listeners have fired.
 * Expenses and settlements must remain read-only through this Firestore instance, including
 * deletions; callable settlement writes cannot introduce optimistic local query changes.
 */
class FirestoreHouseholdFinanceRepository(
    private val firestore: FirebaseFirestore,
    private val settlements: SettlementRepository,
) : HouseholdFinanceRepository, SettlementRepository by settlements {
  override fun observeHouseholdFinances(householdId: String): Flow<HouseholdFinanceSnapshot> {
    require(householdId.isNotBlank() && '/' !in householdId)
    return callbackFlow {
          var parentReady = false
          var expenses: QuerySnapshot? = null
          var payments: QuerySnapshot? = null
          val registrations = mutableListOf<ListenerRegistration>()
          val household =
              firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId)
          try {
            registrations +=
                household.addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                  parentReady = false
                  if (error != null) close(error)
                  else if (value == null) close(IllegalStateException("Missing household snapshot"))
                  else if (value.metadata.committed()) {
                    if (!value.exists() || value.get(TYPE) != FridgeType.SHARED.name) {
                      close(
                          FirebaseFirestoreException(
                              "Shared household required",
                              FirebaseFirestoreException.Code.PERMISSION_DENIED))
                    } else parentReady = true
                  }
                }
            registrations +=
                household.collection(FirestoreCollections.EXPENSES).addSnapshotListener(
                    MetadataChanges.INCLUDE) { value, error ->
                      expenses = value?.takeIf { it.metadata.committed() }
                      if (error != null) close(error)
                      else if (value == null)
                          close(IllegalStateException("Missing expenses snapshot"))
                    }
            registrations +=
                household.collection(SETTLEMENTS).addSnapshotListener(MetadataChanges.INCLUDE) {
                    value,
                    error ->
                  payments = value?.takeIf { it.metadata.committed() }
                  if (error != null) close(error)
                  else if (value == null)
                      close(IllegalStateException("Missing settlements snapshot"))
                }
            registrations +=
                firestore.addSnapshotsInSyncListener {
                  val currentExpenses = expenses
                  val currentPayments = payments
                  if (parentReady && currentExpenses != null && currentPayments != null) {
                    try {
                      trySend(
                          HouseholdFinanceSnapshot(
                              householdId,
                              currentExpenses.documents.map { it.toSharedExpense(householdId) },
                              currentPayments.documents.map { it.toSettlement(householdId) }))
                    } catch (error: Exception) {
                      close(error)
                    }
                  }
                }
            awaitClose {}
          } finally {
            registrations.forEach { it.remove() }
          }
        }
        .buffer(Channel.CONFLATED)
        .distinctUntilChanged()
  }
}

private fun SnapshotMetadata.committed() = !isFromCache && !hasPendingWrites()

/** Rejects malformed, extra-field and path-mismatched records instead of understating debt. */
internal fun DocumentSnapshot.toSettlement(householdId: String): Settlement {
  val fields = requireNotNull(data)
  require(fields.keys == setOf(ID, HOUSEHOLD_ID, PAYER_ID, RECIPIENT_ID, AMOUNT_CENTS))
  require(fields[ID] == id && fields[HOUSEHOLD_ID] == householdId)
  return Settlement(
      id,
      householdId,
      requireNotNull(fields[PAYER_ID] as? String),
      requireNotNull(fields[RECIPIENT_ID] as? String),
      requireNotNull(fields[AMOUNT_CENTS] as? Long))
}

private const val SETTLEMENTS = "settlements"
private const val TYPE = "type"
private const val ID = "id"
private const val HOUSEHOLD_ID = "householdId"
private const val PAYER_ID = "payerId"
private const val RECIPIENT_ID = "recipientId"
private const val AMOUNT_CENTS = "amountCents"
