package com.android.sharemate.model.receipt

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firestore implementation for the ReceiptRepository.
 *
 * Note on Offline Cache: Firestore enables offline persistence by default on Android.
 * This means `addSnapshotListener` will immediately return cached data when offline,
 * and pending writes (like `set` or `delete`) will be queued and synchronized automatically 
 * once the device regains internet connection.
 */
class ReceiptRepositoryFirestore(
    private val db: FirebaseFirestore
) : ReceiptRepository {

    private val collectionPath = "receipts"

    override fun getPrivateReceipts(userId: String): Flow<List<Receipt>> = callbackFlow {
        // Query receipts owned by the user that are NOT part of any household (private receipts)
        val listenerRegistration = db.collection(collectionPath)
            .whereEqualTo("ownerId", userId)
            .whereEqualTo("householdId", null)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ReceiptRepositoryFirestore", "Error fetching private receipts", error)
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

        // Remove the listener when the flow is closed
        awaitClose { listenerRegistration.remove() }
    }

    override fun getSharedReceipts(householdId: String): Flow<List<Receipt>> = callbackFlow {
        // Query receipts shared within a specific household
        val listenerRegistration = db.collection(collectionPath)
            .whereEqualTo("householdId", householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ReceiptRepositoryFirestore", "Error fetching shared receipts", error)
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

        awaitClose { listenerRegistration.remove() }
    }

    override suspend fun addReceipt(receipt: Receipt): String? = suspendCancellableCoroutine { continuation ->
        val docRef = if (receipt.id.isNotEmpty()) {
            db.collection(collectionPath).document(receipt.id)
        } else {
            db.collection(collectionPath).document() // Generates a new ID
        }

        // Ensure the stored receipt has the correct document ID
        val receiptToSave = receipt.copy(id = docRef.id)

        docRef.set(receiptToSave)
            .addOnSuccessListener {
                continuation.resume(docRef.id)
            }
            .addOnFailureListener { e ->
                Log.e("ReceiptRepositoryFirestore", "Error adding receipt", e)
                continuation.resume(null)
            }
    }

    override suspend fun deleteReceipt(receiptId: String): Boolean = suspendCancellableCoroutine { continuation ->
        db.collection(collectionPath).document(receiptId).delete()
            .addOnSuccessListener {
                continuation.resume(true)
            }
            .addOnFailureListener { e ->
                Log.e("ReceiptRepositoryFirestore", "Error deleting receipt", e)
                continuation.resume(false)
            }
    }
}
