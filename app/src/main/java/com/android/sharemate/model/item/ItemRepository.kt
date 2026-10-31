// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import kotlinx.coroutines.flow.Flow

/**
 * Interface définissant les opérations possibles sur les Items. Conformément à l'architecture,
 * cette interface abstrait la source de données (Firestore).
 */
interface ItemRepository {

  /**
   * Récupère un flux (Flow) réactif de tous les items visibles par l'utilisateur dans son frigo
   * actuel. Le Flow s'actualisera automatiquement si les données changent en local ou sur le
   * serveur.
   */
  fun getFridgeItems(userId: String, householdId: String?): Flow<List<Item>>

  /**
   * Récupère un flux (Flow) réactif de tous les items privés de l'utilisateur. Le Flow
   * s'actualisera automatiquement si les données changent en local ou sur le serveur.
   */
  fun getPrivateItems(userId: String): Flow<List<Item>>

  /** Récupère un flux (Flow) réactif des items du foyer, SHARED comme PERSONAL. */
  fun getSharedItems(householdId: String): Flow<List<Item>>

  /**
   * Ajoute un nouvel item (privé ou partagé selon si householdId est défini).
   *
   * @return L'ID généré de l'item, ou null en cas d'erreur.
   */
  suspend fun addItem(item: Item): String?

  /**
   * Attaches a lookup result only if the item still exists, its persisted name exactly matches
   * [expectedName] and it has no image. Checks and the image-only write are atomic; image credits
   * are included in [image]. Pass the saved name used for the lookup, without normalizing it.
   *
   * Returns after server confirmation. This operation requires connectivity and is not queued
   * offline. Cancellation propagates but does not revoke a transaction already submitted to the
   * SDK.
   */
  suspend fun updateItemImageIfAbsent(
      itemId: String,
      expectedName: String,
      image: ItemImage
  ): ItemImageUpdateResult

  /** Supprime un item spécifique. */
  suspend fun deleteItem(itemId: String): Boolean

  /** Updates only editable fields; never transfers ownership or changes sharing. */
  fun updateItem(itemId: String, edit: ItemEdit): ItemWrite

  /**
   * Updates sharing only, preserving ownerId and householdId. Household visibility is unchanged.
   * The server permits only the owner to change sharing; observe [ItemWrite] confirmation for
   * denial.
   */
  fun setItemSharing(itemId: String, sharing: ItemSharing): ItemWrite

  /** Changes lifecycle only. ACTIVE also permits undoing an accidental consume/discard. */
  fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite

  /**
   * Permanently deletes, returning after SDK submission even offline. Missing documents are an
   * idempotent delete. [deleteItem] remains the legacy server-confirmed Boolean operation.
   */
  fun deleteItemQueued(itemId: String): ItemWrite
}
