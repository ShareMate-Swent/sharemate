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
        observe(receipts.whereEqualTo("ownerId", userId)) {
          it.filter { receipt -> receipt.householdId == null }
        }
      }

  override fun getSharedReceipts(householdId: String): Flow<List<Receipt>> =
      if (householdId.isBlank()) {
        flowOf(emptyList())
      } else {
        observe(receipts.whereEqualTo("householdId", householdId))
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
  val storeName = getString("storeName")?.takeIf { it.isNotBlank() } ?: return null
  val ownerId = getString("ownerId")?.takeIf { it.isNotBlank() } ?: return null
  return Receipt(
      id = id,
      storeName = storeName,
      totalAmountCents = getLong("totalAmountCents") ?: 0L,
      date = getTimestamp("date")?.toDate(),
      ownerId = ownerId,
      householdId = getString("householdId")?.takeIf { it.isNotBlank() })
}
