// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.incident

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class IncidentPhotoReferenceTest {
  private val id = UUID.fromString("12345678-1234-1234-1234-123456789abc")

  @Test
  fun referenceRoundTripsWithoutBeingAUrl() {
    val reference = IncidentPhotoReference.forUpload("household_1", "author-1", id)
    assertEquals("incidentPhotos/household_1/author-1/$id", reference.path)
    assertEquals(reference, IncidentPhotoReference(reference.path))
    assertEquals("household_1", reference.householdId)
    assertEquals("author-1", reference.authorId)
    val createdHousehold = "author-1:$id"
    assertEquals(
        createdHousehold,
        IncidentPhotoReference.forUpload(createdHousehold, "author-1", id).householdId)
  }

  @Test
  fun malformedPathsAndNonCanonicalIdsAreRejected() {
    val valid = "incidentPhotos/home/author/$id"
    val paths =
        listOf(
            "",
            "/$valid",
            "$valid/extra",
            "gs://bucket/$valid",
            "https://host/$valid",
            "itemPhotos/home/author/$id",
            "incidentPhotos/home/$id",
            "incidentPhotos/home/author/not-a-uuid",
            "incidentPhotos/home/author/${id.toString().uppercase()}",
            "incidentPhotos/home/author/$id?token=t")
    for (path in paths) assertThrows(path, IllegalArgumentException::class.java) {
      IncidentPhotoReference(path)
    }
    for (segment in listOf("", ".", "..", "a/b", "a\\b", "%2F", "a b", "a\n", "a?b", "a#b")) {
      assertThrows(segment, IllegalArgumentException::class.java) {
        IncidentPhotoReference.forUpload(segment, "author", id)
      }
      assertThrows(segment, IllegalArgumentException::class.java) {
        IncidentPhotoReference.forUpload("home", segment, id)
      }
    }
  }
}
