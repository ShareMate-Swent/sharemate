// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.ui.photo.DiscardPhotoOnDispose
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerLabels
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.PhotoSource
import com.android.sharemate.ui.photo.photoPickerViewModel

private val PreviewSize = 80.dp

@Composable
internal fun PersonalPhotoPicker(
    draft: PersonalPhotoDraft,
    photos: PersonalPhotoController,
    enabled: Boolean,
    camera: PhotoPickerViewModel = photoPickerViewModel("add-item-personal-photo"),
) {
  val state by camera.state.collectAsState()
  DiscardPhotoOnDispose(camera)
  LaunchedEffect(state.confirmed) { if (state.confirmed) camera.reset() }
  TextButton(onClick = camera::open, enabled = enabled && !draft.importing && !state.busy) {
    Text(
        stringResource(
            if (draft.file == null) R.string.personal_photo_add
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
  PhotoPickerDialog(
      camera,
      labels =
          PhotoPickerLabels(
              R.string.personal_photo_add,
              R.string.personal_photo_preview,
              R.string.personal_photo_sources),
      onImageConfirmed = { photos.selectAndWait(it.uri, it.source == PhotoSource.CAMERA) })
}
