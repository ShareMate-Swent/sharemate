// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.android.sharemate.R

private val PreviewSize = 80.dp

@Composable
internal fun PersonalPhotoPicker(
    draft: PersonalPhotoDraft,
    photos: PersonalPhotoController,
    enabled: Boolean,
) {
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (enabled) photos.select(uri?.toString())
      }
  TextButton(
      onClick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      enabled = enabled && !draft.importing) {
        Text(
            stringResource(
                if (draft.file == null) R.string.personal_photo_choose
                else R.string.personal_photo_change))
      }
  draft.file?.let { file ->
    AsyncImage(
        model = file,
        contentDescription = stringResource(R.string.personal_photo_preview),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(PreviewSize))
    TextButton(onClick = photos::discardDraft, enabled = enabled && !draft.importing) {
      Text(stringResource(R.string.personal_photo_remove))
    }
  }
  if (draft.rejected) {
    Text(stringResource(R.string.personal_photo_invalid), color = MaterialTheme.colorScheme.error)
  }
}
