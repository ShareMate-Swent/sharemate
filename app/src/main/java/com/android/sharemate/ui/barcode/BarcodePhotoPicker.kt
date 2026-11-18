// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.barcode

import androidx.compose.runtime.Composable
import com.android.sharemate.R
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerLabels
import com.android.sharemate.ui.photo.PhotoPickerViewModel

internal object BarcodeFormTestTags {
  const val OPEN = "barcode_photo_open"
  const val LOOKUP_ERROR = "barcode_lookup_error"
}

private val barcodeLabels =
    PhotoPickerLabels(
        R.string.barcode_photo_title, R.string.barcode_photo_preview, R.string.barcode_photo_choose)

/** The fridge and purchase scanners both read the original confirmed image. */
@Composable
internal fun BarcodePhotoPicker(
    picker: PhotoPickerViewModel,
    confirmationError: String?,
    onImageConfirmed: (suspend (String) -> Unit)?,
) {
  PhotoPickerDialog(picker, barcodeLabels, confirmationError) { onImageConfirmed?.invoke(it.uri) }
}
