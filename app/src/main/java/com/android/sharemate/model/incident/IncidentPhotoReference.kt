// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.incident

import java.util.UUID

/**
 * Canonical bucket-relative path: `incidentPhotos/{householdId}/{authorId}/{photoId}`.
 * Household/author IDs are nonempty ASCII letters, digits, `_`, `-` or `:`. Photo IDs are lowercase
 * hexadecimal UUIDs in 8-4-4-4-12 form. URLs, traversal segments and additional path components are
 * rejected.
 *
 * The caller stores [path] unchanged in the future incident message (#359). Read photos through
 * [IncidentPhotoRepository.load] with an authenticated session; this is not a public download URL.
 */
data class IncidentPhotoReference(val path: String) {
  init {
    require(PATH.matches(path)) { "Invalid incident photo path" }
  }

  val householdId: String
    get() = path.split('/')[1]

  val authorId: String
    get() = path.split('/')[2]

  companion object {
    // Household IDs also use ':' between the creator UID and the creation operation ID.
    private const val SEGMENT = "[A-Za-z0-9_:-]+"
    private const val PHOTO_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val PATH = Regex("incidentPhotos/$SEGMENT/$SEGMENT/$PHOTO_ID")

    internal fun forUpload(home: String, author: String, id: UUID): IncidentPhotoReference =
        IncidentPhotoReference("incidentPhotos/$home/$author/$id")
  }
}
