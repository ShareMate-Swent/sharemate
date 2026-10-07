package com.android.sharemate.model.image

/** Recherche d'images indépendante du modèle. */
interface FoodImageRepository {
  /**
   * Recherche une image en supprimant les espaces aux extrémités du [title]. Un titre vide ou blanc
   * produit un échec [FoodImageSearchResult.Reason.EMPTY_TITLE] sans requête réseau.
   *
   * Peut être appelée depuis le thread principal. Les erreurs attendues sont exposées dans le
   * résultat ; l'annulation de la coroutine est propagée.
   */
  suspend fun searchByTitle(title: String): FoodImageSearchResult
}
