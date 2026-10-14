// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.image.FoodImageSelection

/** Persistable image reference with optional attribution fields for remote images. */
data class ItemImage(
    val origin: ItemImageOrigin = ItemImageOrigin.PERSONAL_PHOTO,
    val reference: String = "",
    val author: String? = null,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val title: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val licenseVersion: String? = null,
    val authorUrl: String? = null
) {
  companion object {
    fun fromSelection(selection: FoodImageSelection): ItemImage? =
        when (selection) {
          is FoodImageSelection.PersonalPhoto -> ItemImage(reference = selection.reference)
          is FoodImageSelection.RemoteImage ->
              with(selection.image) {
                ItemImage(
                    origin = ItemImageOrigin.REMOTE_IMAGE,
                    reference = url,
                    author = author,
                    sourceName = sourceName,
                    sourceUrl = sourceUrl,
                    title = title,
                    license = license,
                    licenseUrl = licenseUrl,
                    licenseVersion = licenseVersion,
                    authorUrl = authorUrl)
              }
          FoodImageSelection.Generic -> null
        }
  }
}

enum class ItemImageOrigin {
  PERSONAL_PHOTO,
  REMOTE_IMAGE
}
