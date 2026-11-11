// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity

private val PreviewSize = 80.dp

@Composable
internal fun PersonalPhotoPicker(
    draft: PersonalPhotoDraft,
    photos: PersonalPhotoController,
    enabled: Boolean,
    camera: BarcodePhotoViewModel = personalPhotoCamera(),
) {
  val state by camera.state.collectAsState()
  val activity = LocalContext.current.findActivity()
  DisposableEffect(camera) {
    onDispose { if (activity?.isChangingConfigurations != true) camera.discard() }
  }
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
  BarcodePhotoFlow(
      camera,
      title = R.string.personal_photo_add,
      previewDescription = R.string.personal_photo_preview,
      sourceDescription = R.string.personal_photo_sources,
      onImageConfirmed = { uri -> photos.selectAndWait(uri, camera.state.value.fromCamera) })
}

@Composable
private fun personalPhotoCamera(): BarcodePhotoViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = "add-item-personal-photo",
      factory =
          viewModelFactory {
            initializer {
              BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
            }
          })
}
