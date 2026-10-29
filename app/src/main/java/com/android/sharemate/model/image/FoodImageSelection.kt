// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_MAX_ATTEMPTS
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_RETRY_DELAY_MS
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_TIMEOUT_MS
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Visuel choisi pour un aliment, sans dépendance à l'interface Android. */
sealed interface FoodImageSelection {
  data class PersonalPhoto(val reference: String) : FoodImageSelection

  data class RemoteImage(val image: FoodImage) : FoodImageSelection

  data object Generic : FoodImageSelection
}

/**
 * Choisit la source visuelle d'un aliment.
 *
 * Le ViewModel recherche une image après avoir enregistré l'aliment avec un placeholder. Un échec
 * de recherche conserve ce placeholder sans remettre en cause la création.
 */
class FoodImageSelector(private val repository: FoodImageRepository) {
  suspend fun select(title: String, personalPhotoReference: String? = null): FoodImageSelection {
    val personalReference = personalPhotoReference?.takeIf { it.isNotBlank() }
    if (personalReference != null) {
      return FoodImageSelection.PersonalPhoto(personalReference)
    }

    return when (val result = repository.searchByTitle(title)) {
      is FoodImageSearchResult.Found -> FoodImageSelection.RemoteImage(result.image)
      FoodImageSearchResult.NotFound,
      is FoodImageSearchResult.Failure -> FoodImageSelection.Generic
    }
  }

  /**
   * Retries temporary service failures after creation, without extending any individual network
   * attempt's deadline. Cancellation from the owning session always propagates.
   */
  suspend fun selectAutomatic(title: String): FoodImageSelection {
    repeat(IMAGE_LOOKUP_MAX_ATTEMPTS) { attempt ->
      currentCoroutineContext().ensureActive()
      val result =
          try {
            withTimeoutOrNull(IMAGE_LOOKUP_TIMEOUT_MS) { repository.searchByTitle(title) }
          } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            FoodImageSearchResult.Failure(FoodImageSearchResult.Reason.NETWORK)
          }
      currentCoroutineContext().ensureActive()
      when (result) {
        is FoodImageSearchResult.Found -> return FoodImageSelection.RemoteImage(result.image)
        FoodImageSearchResult.NotFound -> return FoodImageSelection.Generic
        is FoodImageSearchResult.Failure ->
            if (!result.isRetryable()) return FoodImageSelection.Generic
        null -> Unit // Only this attempt's own deadline expired.
      }
      if (attempt + 1 < IMAGE_LOOKUP_MAX_ATTEMPTS) {
        delay(IMAGE_LOOKUP_RETRY_DELAY_MS)
      }
    }
    return FoodImageSelection.Generic
  }

  private fun FoodImageSearchResult.Failure.isRetryable(): Boolean =
      reason == FoodImageSearchResult.Reason.NETWORK ||
          (reason == FoodImageSearchResult.Reason.HTTP &&
              (httpStatus == 408 || httpStatus == 429 || (httpStatus ?: 0) in 500..599))
}
