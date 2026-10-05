package com.android.sharemate.model.item

import kotlinx.coroutines.flow.Flow

/**
 * Interface définissant les opérations possibles sur les Items. Conformément à l'architecture,
 * cette interface abstrait la source de données (Firestore).
 */
interface ItemRepository {

    /**
     * Récupère un flux (Flow) réactif de tous les items privés de l'utilisateur. Le Flow
     * s'actualisera automatiquement si les données changent en local ou sur le serveur.
     */
    fun getPrivateItems(userId: String): Flow<List<Item>>

    /** Récupère un flux (Flow) réactif de tous les items partagés pour un foyer donné. */
    fun getSharedItems(householdId: String): Flow<List<Item>>

    /**
     * Ajoute un nouvel item (privé ou partagé selon si householdId est défini).
     *
     * @return L'ID généré de l'item, ou null en cas d'erreur.
     */
    suspend fun addItem(item: Item): String?

    /** Supprime un item spécifique. */
    suspend fun deleteItem(itemId: String): Boolean
}