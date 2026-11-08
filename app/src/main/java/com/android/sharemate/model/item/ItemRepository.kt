// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import kotlinx.coroutines.flow.Flow

/**
 * Operations available on items. In line with the architecture, this interface abstracts the data
 * source (Firestore).
 */
interface ItemRepository {

  /**
   * Reactive stream of the items of a household fridge that [userId] may see: every SHARED item of
   * the household plus the PERSONAL items owned by [userId]. The personal items of other members
   * are never returned. Blank arguments give an empty stream.
   *
   * The server only accepts this query from a current member of the household: it fails with a
   * permission error for a user who left it or never belonged to it.
   */
  fun getFridgeItems(householdId: String, userId: String): Flow<List<Item>>

  /**
   * Adds a new item. [Item.householdId] is required; [Item.sharing] tells whether it is private to
   * its owner or shared with the household.
   *
   * @return The generated item ID, or null on error.
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

  /** Deletes a specific item. */
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
