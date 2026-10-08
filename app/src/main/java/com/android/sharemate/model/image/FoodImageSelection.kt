// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.image

/** Visuel choisi pour un aliment, sans dépendance à l'interface Android. */
sealed interface FoodImageSelection {
  data class PersonalPhoto(val reference: String) : FoodImageSelection

  data class RemoteImage(val image: FoodImage) : FoodImageSelection

  data object Generic : FoodImageSelection
}

/**
 * Choisit la source visuelle d'un aliment.
 *
 * Le ViewModel du formulaire appellera cette fonction lors de la validation, puis enregistrera la
 * référence obtenue avec l'aliment. Le branchement au formulaire et à l'enregistrement reste à
 * réaliser ; un échec de recherche ne doit pas empêcher cet enregistrement.
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
}
