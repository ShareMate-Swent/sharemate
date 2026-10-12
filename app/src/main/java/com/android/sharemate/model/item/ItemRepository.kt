// Co-authored-by: OpenAI Codex <noreply@openai.com>
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

  /** Updates only editable fields; never transfers ownership or changes sharing. */
  fun updateItem(itemId: String, edit: ItemEdit): ItemWrite

  /** Changes lifecycle only. ACTIVE also permits undoing an accidental consume/discard. */
  fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite

  /**
   * Permanently deletes, returning after SDK submission even offline. Missing documents are an
   * idempotent delete. [deleteItem] remains the legacy server-confirmed Boolean operation.
   */
  fun deleteItemQueued(itemId: String): ItemWrite
}
