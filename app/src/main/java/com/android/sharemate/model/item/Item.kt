// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.util.Date

/**
 * Représente un aliment ou un reçu dans l'application.
 *
 * @property id Identifiant unique de l'item (généré par Firestore).
 * @property name Nom de l'aliment (ex: "Lait").
 * @property ownerId L'UID Firebase de l'utilisateur qui a créé l'item.
 * @property householdId L'ID du foyer si l'item est partagé. Si null, l'item est strictement privé.
 * @property expirationDate Date de péremption de l'aliment.
 * @property category Optional category entered by the user.
 */
data class Item(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val householdId: String? = null,
    val expirationDate: Date? = null,
    val category: String? = null
) {
  /** Helper pour déterminer facilement si un item est partagé ou privé côté UI. */
  val isShared: Boolean
    get() = householdId != null
}
