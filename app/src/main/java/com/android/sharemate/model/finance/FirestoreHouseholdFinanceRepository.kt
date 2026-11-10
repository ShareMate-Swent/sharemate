// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.finance.FinanceFields.AMOUNT_CENTS
import com.android.sharemate.model.finance.FinanceFields.HOUSEHOLD_ID
import com.android.sharemate.model.finance.FinanceFields.ID
import com.android.sharemate.model.finance.FinanceFields.PAYER_ID
import com.android.sharemate.model.finance.FinanceFields.RECIPIENT_ID
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/**
 * Publishes ledger snapshots after all affected Firestore listeners have fired. Cached or pending
 * data is provisional ([HouseholdFinanceSnapshot.isSynced] is false). Expenses and settlements must
 * remain read-only through this Firestore instance, including deletions; callable writes cannot
 * introduce optimistic local query changes.
 */
class FirestoreHouseholdFinanceRepository(
    private val firestore: FirebaseFirestore,
    private val settlements: SettlementRepository,
) : HouseholdFinanceRepository, SettlementRepository by settlements {
  override fun observeHouseholdFinances(householdId: String): Flow<HouseholdFinanceSnapshot> {
    require(householdId.isNotBlank() && '/' !in householdId)
    return callbackFlow {
          val collector = FinanceSnapshotCollector(householdId, { trySend(it) }, { close(it) })
          val registrations = mutableListOf<ListenerRegistration>()
          val household =
              firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId)
          try {
            registrations +=
                household.addSnapshotListener(MetadataChanges.INCLUDE, collector::onParent)
            registrations +=
                household
                    .collection(FirestoreCollections.EXPENSES)
                    .addSnapshotListener(MetadataChanges.INCLUDE, collector::onExpenses)
            registrations +=
                household
                    .collection(FirestoreCollections.SETTLEMENTS)
                    .addSnapshotListener(MetadataChanges.INCLUDE, collector::onPayments)
            registrations += firestore.addSnapshotsInSyncListener(collector::onSync)
            awaitClose {}
          } finally {
            registrations.forEach { it.remove() }
          }
        }
        .buffer(Channel.CONFLATED)
        .distinctUntilChanged()
  }
}

/** Holds one subscription's latest sources; publication happens only at the SDK sync boundary. */
private class FinanceSnapshotCollector(
    private val householdId: String,
    private val publish: (HouseholdFinanceSnapshot) -> Unit,
    private val fail: (Throwable) -> Unit,
) {
  private var parent: DocumentSnapshot? = null
  private var expenses: QuerySnapshot? = null
  private var payments: QuerySnapshot? = null
  private var failed = false

  fun onParent(value: DocumentSnapshot?, error: FirebaseFirestoreException?) = receive {
    if (error != null) throw error
    parent =
        checkNotNull(value) { "Missing household snapshot" }.also { it.requireSharedHousehold() }
  }

  fun onExpenses(value: QuerySnapshot?, error: FirebaseFirestoreException?) = receive {
    if (error != null) throw error
    expenses = checkNotNull(value) { "Missing expenses snapshot" }
  }

  fun onPayments(value: QuerySnapshot?, error: FirebaseFirestoreException?) = receive {
    if (error != null) throw error
    payments = checkNotNull(value) { "Missing settlements snapshot" }
  }

  fun onSync() = receive {
    val currentParent = parent ?: return@receive
    val currentExpenses = expenses ?: return@receive
    val currentPayments = payments ?: return@receive
    publish(
        HouseholdFinanceSnapshot(
            householdId,
            currentExpenses.documents.map { it.toSharedExpense(householdId) },
            currentPayments.documents.map { it.toSettlement(householdId) },
            isSynced =
                currentParent.metadata.isServerConfirmed() &&
                    currentExpenses.metadata.isServerConfirmed() &&
                    currentPayments.metadata.isServerConfirmed()))
  }

  private inline fun receive(action: () -> Unit) {
    if (failed) return
    try {
      action()
    } catch (error: Exception) {
      failed = true
      fail(error)
    }
  }
}

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
