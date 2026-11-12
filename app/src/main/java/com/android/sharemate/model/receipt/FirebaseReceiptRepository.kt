// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

class FirebaseReceiptRepository(private val firestore: FirebaseFirestore) : ReceiptRepository {

  private val receipts
    get() = firestore.collection(FirestoreCollections.RECEIPTS)

  override fun getPrivateReceipts(userId: String): Flow<List<Receipt>> =
      if (userId.isBlank()) {
        flowOf(emptyList())
      } else {
        observe(receipts.whereEqualTo(ReceiptFields.OWNER_ID, userId)) {
          it.filter { receipt -> receipt.householdId == null }
        }
      }

  override fun getSharedReceipts(householdId: String): Flow<List<Receipt>> =
      if (householdId.isBlank()) {
        flowOf(emptyList())
      } else {
        observe(receipts.whereEqualTo(ReceiptFields.HOUSEHOLD_ID, householdId))
      }

  override suspend fun addReceipt(receipt: Receipt): String? {
    if (receipt.storeName.isBlank() || receipt.ownerId.isBlank()) return null
    return try {
      val document = receipts.document()
      val savedReceipt =
          receipt.copy(
              id = document.id,
              storeName = receipt.storeName.trim(),
              householdId = receipt.householdId?.takeIf { it.isNotBlank() })
      document.set(savedReceipt).await()
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  override suspend fun deleteReceipt(receiptId: String): Boolean {
    if (receiptId.isBlank()) return false
    return try {
      receipts.document(receiptId).delete().await()
      true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    }
  }

  private fun observe(
      query: Query,
      transform: (List<Receipt>) -> List<Receipt> = { it },
  ): Flow<List<Receipt>> =
      callbackFlow {
            val registration =
                query.addSnapshotListener { snapshot, error ->
                  if (error != null) {
                    close(error)
                    return@addSnapshotListener
                  }
                  val parsed = snapshot?.documents?.mapNotNull { it.toReceiptOrNull() }.orEmpty()
                  trySend(transform(parsed))
                }
            awaitClose { registration.remove() }
          }
          .distinctUntilChanged()
}

internal fun DocumentSnapshot.toReceiptOrNull(): Receipt? {
  val storeName = getString(ReceiptFields.STORE_NAME)?.takeIf { it.isNotBlank() } ?: return null
  val ownerId = getString(ReceiptFields.OWNER_ID)?.takeIf { it.isNotBlank() } ?: return null
  return Receipt(
      id = id,
      storeName = storeName,
      totalAmountCents = getLong(ReceiptFields.TOTAL_AMOUNT_CENTS) ?: 0L,
      date = getTimestamp(ReceiptFields.DATE)?.toDate(),
      ownerId = ownerId,
      householdId = getString(ReceiptFields.HOUSEHOLD_ID)?.takeIf { it.isNotBlank() })
}

/** Fields of a receipt document in Firestore. */
private object ReceiptFields {
  const val OWNER_ID = "ownerId"
  const val HOUSEHOLD_ID = "householdId"
  const val STORE_NAME = "storeName"
  const val TOTAL_AMOUNT_CENTS = "totalAmountCents"
  const val DATE = "date"
}
