// Co-authored-by: OpenAI Codex <noreply@openai.com>
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

  /** Optional lead image of an English article matching the full item name. */
  suspend fun exactArticleImage(title: String): FoodImageSearchResult =
      FoodImageSearchResult.NotFound

  /** Final search restricted to file titles when the provider supports it. */
  suspend fun searchFileTitles(title: String, candidateLimit: Int): FoodImageSearchResult =
      searchByTitle(title, candidateLimit)

  /**
   * Searches with a positive candidate limit. Providers without this capability keep their lookup.
   */
  suspend fun searchByTitle(title: String, candidateLimit: Int): FoodImageSearchResult =
      searchByTitle(title)
}
