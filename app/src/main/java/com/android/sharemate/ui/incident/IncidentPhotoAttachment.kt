// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.incident

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.ui.barcode.BarcodePhotoFlow
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.barcode.findActivity

/** Stable tags for the attachment controls and confirmed preview. */
object IncidentPhotoTags {
  const val SELECT = "incident_photo_select"
  const val PREVIEW = "incident_photo_preview"
  const val REMOVE = "incident_photo_remove"
  const val ERROR = "incident_photo_error"
}

/**
 * Reusable photo portion of an incident draft. The parent owns [viewModel] and observes its local
 * file for future submission; this component neither creates an incident nor uploads its photo.
 * Scope the ViewModel to that draft so its private file is released when the draft ends.
 */
@Composable
fun IncidentPhotoAttachment(
    viewModel: IncidentPhotoViewModel,
    modifier: Modifier = Modifier,
    camera: BarcodePhotoViewModel = incidentPhotoCamera(),
) {
  val draft by viewModel.state.collectAsState()
  val picker by camera.state.collectAsState()
  val context = LocalContext.current
  val activity = context.findActivity()
  DisposableEffect(camera) {
    onDispose { if (activity?.isChangingConfigurations != true) camera.discard() }
  }
  LaunchedEffect(picker.confirmed) { if (picker.confirmed) camera.reset() }
  Column(modifier) {
    TextButton(
        onClick = camera::open,
        enabled = !draft.importing && !picker.busy,
        modifier = Modifier.testTag(IncidentPhotoTags.SELECT)) {
          Text(
              stringResource(
                  if (draft.file == null) R.string.incident_photo_add
                  else R.string.incident_photo_replace))
        }
    draft.file?.let { file ->
      AsyncImage(
          model = ImageRequest.Builder(context).data(file).size(1280).build(),
          contentDescription = stringResource(R.string.incident_photo_preview),
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(96.dp).testTag(IncidentPhotoTags.PREVIEW))
      TextButton(
          onClick = viewModel::remove,
          enabled = !draft.importing,
          modifier = Modifier.testTag(IncidentPhotoTags.REMOVE)) {
            Text(stringResource(R.string.incident_photo_remove))
          }
    }
    if (draft.importing) CircularProgressIndicator()
    if (draft.rejected) {
      Text(
          stringResource(R.string.incident_photo_invalid),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(IncidentPhotoTags.ERROR))
    }
  }
  BarcodePhotoFlow(
      camera,
      title = R.string.incident_photo_add,
      previewDescription = R.string.incident_photo_preview,
      sourceDescription = R.string.incident_photo_sources,
      confirmationError = stringResource(R.string.incident_photo_invalid),
      onImageConfirmed = { uri -> viewModel.selectAndWait(uri, camera.state.value.fromCamera) })
}

@Composable
private fun incidentPhotoCamera(): BarcodePhotoViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = "incident-photo-selection",
      factory =
          viewModelFactory {
            initializer {
              BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), createSavedStateHandle())
            }
          })
}
