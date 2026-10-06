package com.android.sharemate.model.item

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firestore implementation for the ItemRepository.
 *
 * Note on Offline Cache: Firestore enables offline persistence by default on Android.
 * This means `addSnapshotListener` will immediately return cached data when offline,
 * and pending writes (like `set` or `delete`) will be queued and synchronized automatically 
 * once the device regains internet connection.
 */
class ItemRepositoryFirestore(
    private val db: FirebaseFirestore
) : ItemRepository {

    private val collectionPath = "items"

    override fun getPrivateItems(userId: String): Flow<List<Item>> = callbackFlow {
        // Query items owned by the user that are NOT part of any household (private items)
        val listenerRegistration = db.collection(collectionPath)
            .whereEqualTo("ownerId", userId)
            .whereEqualTo("householdId", null)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ItemRepositoryFirestore", "Error fetching private items", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val items = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(Item::class.java)?.copy(id = doc.id)
                    }
                    trySend(items)
                }
            }

        // Remove the listener when the flow is closed (e.g., when the ViewModel is cleared)
        awaitClose { listenerRegistration.remove() }
    }

    override fun getSharedItems(householdId: String): Flow<List<Item>> = callbackFlow {
        // Query items shared within a specific household
        val listenerRegistration = db.collection(collectionPath)
            .whereEqualTo("householdId", householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ItemRepositoryFirestore", "Error fetching shared items", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val items = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(Item::class.java)?.copy(id = doc.id)
                    }
                    trySend(items)
                }
            }

        awaitClose { listenerRegistration.remove() }
    }

    override suspend fun addItem(item: Item): String? = suspendCancellableCoroutine { continuation ->
        val docRef = if (item.id.isNotEmpty()) {
            db.collection(collectionPath).document(item.id)
        } else {
            db.collection(collectionPath).document() // Generates a new ID
        }

        // Ensure the stored item has the correct document ID
        val itemToSave = item.copy(id = docRef.id)

        docRef.set(itemToSave)
            .addOnSuccessListener {
                continuation.resume(docRef.id)
            }
            .addOnFailureListener { e ->
                Log.e("ItemRepositoryFirestore", "Error adding item", e)
                continuation.resume(null)
            }
    }

    override suspend fun deleteItem(itemId: String): Boolean = suspendCancellableCoroutine { continuation ->
        db.collection(collectionPath).document(itemId).delete()
            .addOnSuccessListener {
                continuation.resume(true)
            }
            .addOnFailureListener { e ->
                Log.e("ItemRepositoryFirestore", "Error deleting item", e)
                continuation.resume(false)
            }
    }
}
