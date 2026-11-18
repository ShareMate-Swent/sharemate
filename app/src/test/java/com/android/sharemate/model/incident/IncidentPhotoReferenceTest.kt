// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.incident

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Tests canonical Storage references and rejects malformed paths and identifiers. */
class IncidentPhotoReferenceTest {
  private val id = UUID.fromString("12345678-1234-1234-1234-123456789abc")

  @Test
  fun referenceRoundTripsWithoutBeingAUrl() {
    val reference = IncidentPhotoReference.forUpload("household_1", "author-1", id)
    assertEquals("${IncidentPhotoReference.ROOT}/household_1/author-1/$id", reference.path)
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
    val valid = "${IncidentPhotoReference.ROOT}/home/author/$id"
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
    for (path in paths) {
      val failure =
          assertThrows(path, IllegalArgumentException::class.java) { IncidentPhotoReference(path) }
      assertEquals("Invalid incident photo path", failure.message)
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
