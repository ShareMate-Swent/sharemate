// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import com.google.firebase.firestore.Exclude
import java.util.Date

/**
 * Represente un aliment ou un reçu dans l'application
 *
 * @property id Identifiant unique de l'item (généré par Firestore).
 * @property name Nom de l'aliment (ex: "Lait").
 * @property ownerId L'UID Firebase de l'utilisateur qui a créé l'item.
 * @property householdId L'ID du household (frigo) contenant l'item. Jamais vide : un item
 *   appartient toujours à un household.
 * @property expirationDate Date de péremption de l'aliment.
 * @property category Optional category entered by the user.
 * @property image Optional image reference and attribution; null uses the local placeholder.
 * @property sharing Whether the item is visible to the whole household ([ItemSharing.SHARED]) or
 *   only to its owner ([ItemSharing.PERSONAL], the default).
 */
data class Item(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val householdId: String,
    val expirationDate: Date? = null,
    val category: String? = null,
    val image: ItemImage? = null,
    /** Positive count of units. */
    val quantity: Int = 1,
    val status: ItemStatus = ItemStatus.ACTIVE,
    /** Items are private to their owner unless explicitly shared. */
    val sharing: ItemSharing = ItemSharing.PERSONAL,
) {
  init {
    require(householdId.isNotBlank()) { "An item must belong to a household" }
  }

  /** Derived for callers only; Firestore persists [sharing] as the single sharing field. */
  @get:Exclude
  val isShared: Boolean
    get() = sharing == ItemSharing.SHARED
}
