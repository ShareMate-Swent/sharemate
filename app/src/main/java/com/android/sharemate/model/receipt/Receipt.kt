package com.android.sharemate.model.receipt

import java.util.Date

/**
 * Représente un reçu d'achat dans l'application. Généralisation de la séparation Privé / Commun
 * appliquée aux reçus.
 *
 * @property id Identifiant unique du reçu (généré par Firestore).
 * @property storeName Nom du magasin (ex: "Migros").
 * @property totalAmount Montant total du reçu.
 * @property date Date de l'achat.
 * @property ownerId L'UID Firebase de l'utilisateur qui a ajouté le reçu.
 * @property householdId L'ID du foyer si le reçu est partagé. Si null, le reçu est strictement
 *   privé.
 */
data class Receipt(
    val id: String = "",
    val storeName: String = "",
    val totalAmount: Double = 0.0,
    val date: Date? = null,
    val ownerId: String = "",
    val householdId: String? = null
) {
  val isShared: Boolean
    get() = householdId != null
}
