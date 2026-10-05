package com.android.sharemate.model.item

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * TODO : edit this Firestore Repository to implement authentication
 */
class ItemRepositoryFirestore(
    private val db: FirebaseFirestore
) : ItemRepository {

    private val collectionPath = "items"

    /**
     * Récupère les items privés (householdId est null ET ownerId correspond à l'utilisateur).
     * callbackFlow permet d'écouter les mises à jour en temps réel (même hors ligne depuis le cache).
     */
    override fun getPrivateItems(userId: String): Flow<List<Item>> = callbackFlow {
        val subscription = db.collection(collectionPath)
            .whereEqualTo("ownerId", userId)
            .whereEqualTo("householdId", null) // Filtre crucial pour le privé
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ItemRepository", "Error fetching private items", error)
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

        // Nettoyage de l'écouteur lorsque le Flow n'est plus collecté (ex: UI détruite)
        awaitClose { subscription.remove() }
    }

    /**
     * Récupère les items partagés (pour un foyer spécifique).
     */
    override fun getSharedItems(householdId: String): Flow<List<Item>> = callbackFlow {
        val subscription = db.collection(collectionPath)
            .whereEqualTo("householdId", householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ItemRepository", "Error fetching shared items", error)
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

        awaitClose { subscription.remove() }
    }

    override suspend fun addItem(item: Item): String? {
        return try {
            val documentReference = db.collection(collectionPath).add(item).await()
            documentReference.id
        } catch (e: Exception) {
            Log.e("ItemRepository", "Error adding item", e)
            null
        }
    }

    override suspend fun deleteItem(itemId: String): Boolean {
        return try {
            db.collection(collectionPath).document(itemId).delete().await()
            true
        } catch (e: Exception) {
            Log.e("ItemRepository", "Error deleting item", e)
            false
        }
    }
}
