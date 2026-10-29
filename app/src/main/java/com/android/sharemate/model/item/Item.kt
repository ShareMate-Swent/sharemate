// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.google.firebase.firestore.Exclude
import java.util.Date

/**
 * Représente un aliment ou un reçu dans l'application
 *
 * @property id Identifiant unique de l'item (généré par Firestore).
 * @property name Nom de l'aliment (ex: "Lait").
 * @property ownerId L'UID Firebase de l'utilisateur qui a créé l'item.
 * @property householdId L'ID du foyer contenant l'item. Si null, l'item est strictement privé.
 * @property expirationDate Date de péremption de l'aliment.
 * @property category Optional category entered by the user.
 * @property image Optional image reference and attribution; null uses the local placeholder.
 */
data class Item(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val householdId: String? = null,
    val expirationDate: Date? = null,
    val category: String? = null,
    val image: ItemImage? = null,
    /** Positive count of units; old documents default to one unit. */
    val quantity: Int = 1,
    val status: ItemStatus = ItemStatus.ACTIVE,
    /** Legacy household items are shared; standalone items remain owner-only. */
    val sharing: ItemSharing =
        if (householdId == null) ItemSharing.PERSONAL else ItemSharing.SHARED,
) {
  /** Derived for callers only; Firestore persists [sharing] as the single sharing field. */
  @get:Exclude
  val isShared: Boolean
    get() = householdId != null && sharing == ItemSharing.SHARED
}
