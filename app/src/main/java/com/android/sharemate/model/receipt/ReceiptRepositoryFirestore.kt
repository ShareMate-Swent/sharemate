package com.android.sharemate.model.receipt

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * TODO : edit this Firestore Repository to implement authentication
 */
class ReceiptRepositoryFirestore(
    private val db: FirebaseFirestore
) : ReceiptRepository {

    private val collectionPath = "receipts"

    override fun getPrivateReceipts(userId: String): Flow<List<Receipt>> = callbackFlow {
        val subscription = db.collection(collectionPath)
            .whereEqualTo("ownerId", userId)
            .whereEqualTo("householdId", null) 
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ReceiptRepository", "Error fetching private receipts", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val receipts = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(Receipt::class.java)?.copy(id = doc.id)
                    }
                    trySend(receipts)
                }
            }

        awaitClose { subscription.remove() }
    }

    override fun getSharedReceipts(householdId: String): Flow<List<Receipt>> = callbackFlow {
        val subscription = db.collection(collectionPath)
            .whereEqualTo("householdId", householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ReceiptRepository", "Error fetching shared receipts", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val receipts = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(Receipt::class.java)?.copy(id = doc.id)
                    }
                    trySend(receipts)
                }
            }

        awaitClose { subscription.remove() }
    }

    override suspend fun addReceipt(receipt: Receipt): String? {
        return try {
            val documentReference = db.collection(collectionPath).add(receipt).await()
            documentReference.id
        } catch (e: Exception) {
            Log.e("ReceiptRepository", "Error adding receipt", e)
            null
        }
    }

    override suspend fun deleteReceipt(receiptId: String): Boolean {
        return try {
            db.collection(collectionPath).document(receiptId).delete().await()
            true
        } catch (e: Exception) {
            Log.e("ReceiptRepository", "Error deleting receipt", e)
            false
        }
    }
}
