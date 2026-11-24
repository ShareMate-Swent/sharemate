// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException

/** Requirements for images selected from the gallery. Camera captures use their own bounds. */
enum class PhotoImportPolicy(internal val maxBytes: Long) {
  STANDARD(MAX_INPUT_PHOTO_BYTES),
  PERSONAL(PersonalPhotoRepository.MAX_BYTES.toLong());

  internal fun validateContent(file: File) {
    if (this != PERSONAL) return
    try {
      validatePersonalGalleryPhoto(file)
    } catch (failure: IllegalArgumentException) {
      throw reject(
          PhotoImportRejection.PERSONAL_REQUIREMENTS,
          "Personal photos must be JPEG or PNG",
          failure)
    } catch (failure: ImageDecoder.DecodeException) {
      if (failure.error != ImageDecoder.DecodeException.SOURCE_INCOMPLETE &&
          failure.error != ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA)
          throw failure
      throw reject(PhotoImportRejection.PERSONAL_REQUIREMENTS, "Image cannot be decoded", failure)
    }
  }

  internal fun reject(
      reason: PhotoImportRejection,
      message: String,
      cause: Throwable? = null,
  ): IOException =
      if (this == PERSONAL) PhotoImportValidationException(reason, message, cause)
      else IOException(message, cause)
}

/** Validation failures that have actionable guidance in the personal photo picker. */
enum class PhotoImportRejection {
  PERSONAL_REQUIREMENTS,
  PIXEL_LIMIT,
}

internal class PhotoImportValidationException(
    val rejection: PhotoImportRejection,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
