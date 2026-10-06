// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

/** Le choix d'un visuel de secours appartient à l'interface utilisateur. */
sealed interface FoodImageSearchResult {
  data class Found(val image: FoodImage) : FoodImageSearchResult

  object NotFound : FoodImageSearchResult

  data class Failure(val reason: Reason, val httpStatus: Int? = null) : FoodImageSearchResult

  enum class Reason {
    EMPTY_TITLE,
    NETWORK,
    HTTP,
    INVALID_RESPONSE
  }
}
