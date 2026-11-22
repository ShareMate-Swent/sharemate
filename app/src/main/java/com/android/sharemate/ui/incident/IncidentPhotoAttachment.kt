// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.incident

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidPersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.ui.photo.DiscardPhotoOnDispose
import com.android.sharemate.ui.photo.PREVIEW_MAX_PIXELS
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerLabels
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.PhotoSource
import com.android.sharemate.ui.photo.photoPickerViewModel
import com.android.sharemate.ui.theme.Dimens

/** Stable selectors for the incident-specific controls, independent of the shared photo dialog. */
object IncidentPhotoTags {
  const val SELECT = "incident_photo_select"
  const val PREVIEW = "incident_photo_preview"
  const val REMOVE = "incident_photo_remove"
  const val IMPORTING = "incident_photo_importing"
  const val ERROR = "incident_photo_error"
}

/**
 * Camera/gallery attachment controls for an incident draft. The host owns [viewModel] and reads its
 * confirmed file for submission. Use one picker per draft; multiple attachments in the same
 * ViewModelStore require independent draft ViewModels and pickers. Dismissing the picker never
 * removes the confirmed attachment. No upload or incident form is introduced here.
 */
@Composable
fun IncidentPhotoAttachment(
    viewModel: IncidentPhotoViewModel,
    modifier: Modifier = Modifier,
    picker: PhotoPickerViewModel = photoPickerViewModel(INCIDENT_PICKER_KEY),
) {
  val draft by viewModel.state.collectAsStateWithLifecycle()
  val selection by picker.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val importError =
      stringResource(R.string.incident_photo_invalid, PersonalPhotoRepository.MAX_MEBIBYTES)
  DiscardPhotoOnDispose(picker)
  LaunchedEffect(selection.confirmed) { if (selection.confirmed) picker.reset() }
  LaunchedEffect(selection.visible) { if (selection.visible) viewModel.clearRejection() }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
    TextButton(
        onClick = picker::open,
        enabled = !draft.importing && !selection.busy,
        modifier = Modifier.testTag(IncidentPhotoTags.SELECT)) {
          Text(
              stringResource(
                  if (draft.file == null) R.string.incident_photo_add
                  else R.string.incident_photo_replace))
        }
    draft.file?.let { file ->
      AsyncImage(
          model = ImageRequest.Builder(context).data(file).size(PREVIEW_MAX_PIXELS).build(),
          contentDescription = stringResource(R.string.incident_photo_preview),
          contentScale = ContentScale.Fit,
          modifier = Modifier.size(Dimens.attachmentPreviewSize).testTag(IncidentPhotoTags.PREVIEW))
      TextButton(
          onClick = viewModel::remove,
          enabled = !draft.importing,
          modifier = Modifier.testTag(IncidentPhotoTags.REMOVE)) {
            Text(stringResource(R.string.incident_photo_remove))
          }
    }
    if (draft.importing) {
      CircularProgressIndicator(
          modifier = Modifier.size(Dimens.progressSize).testTag(IncidentPhotoTags.IMPORTING))
    }
    if (draft.rejected) {
      Text(
          importError,
          color = MaterialTheme.colorScheme.error,
          modifier =
              Modifier.testTag(IncidentPhotoTags.ERROR).semantics {
                liveRegion = LiveRegionMode.Polite
              })
    }
  }
  PhotoPickerDialog(
      picker,
      labels =
          PhotoPickerLabels(
              R.string.incident_photo_add,
              R.string.incident_photo_preview,
              R.string.incident_photo_sources),
      confirmationError = importError,
      onImageConfirmed = { viewModel.selectAndWait(it.uri, it.source == PhotoSource.CAMERA) })
}

/** Creates the attachment owner in the host draft's ViewModel store using a stable [key]. */
@Composable
fun incidentPhotoViewModel(key: String): IncidentPhotoViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = key,
      factory =
          viewModelFactory {
            initializer { IncidentPhotoViewModel(AndroidPersonalPhotoFiles(context)) }
          })
}

private const val INCIDENT_PICKER_KEY = "incident-photo-selection"
