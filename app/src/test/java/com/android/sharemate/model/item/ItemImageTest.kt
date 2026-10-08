// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.image.FoodImage
import com.android.sharemate.model.image.FoodImageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemImageTest {
  @Test
  fun personalPhotoKeepsItsExactReferenceAndOrigin() {
    val reference = " content://photos/food?id=42 "
    val image = ItemImage.fromSelection(FoodImageSelection.PersonalPhoto(reference))

    assertEquals(ItemImage(origin = ItemImageOrigin.PERSONAL_PHOTO, reference = reference), image)
    assertEquals(image, Item(image = image).copy(id = "saved-id").image)
  }

  @Test
  fun genericSelectionHasNoStoredImage() {
    assertNull(ItemImage.fromSelection(FoodImageSelection.Generic))
  }

  @Test
  fun remoteImagePreservesMissingOptionalCredits() {
    val remote =
        FoodImage(
            url = "https://example.org/image.jpg",
            author = null,
            sourceName = null,
            sourceUrl = "https://example.org/photo",
            title = null,
            license = "cc0",
            licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
            licenseVersion = null,
            authorUrl = null)

    assertEquals(
        ItemImage(
            origin = ItemImageOrigin.REMOTE_IMAGE,
            reference = remote.url,
            sourceUrl = remote.sourceUrl,
            license = remote.license,
            licenseUrl = remote.licenseUrl),
        ItemImage.fromSelection(FoodImageSelection.RemoteImage(remote)))
  }
}
