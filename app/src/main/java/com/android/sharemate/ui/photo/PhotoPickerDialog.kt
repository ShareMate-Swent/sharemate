// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.model.image.MAX_INPUT_PHOTO_PIXELS
import com.android.sharemate.model.image.PhotoImportRejection
import com.android.sharemate.ui.theme.Dimens

data class PhotoPickerLabels(
    @StringRes val title: Int,
    @StringRes val previewDescription: Int,
    @StringRes val sourceDescription: Int,
)

/** Captures or imports a photo and waits for the caller to finish using the confirmed selection. */
@Composable
fun PhotoPickerDialog(
    viewModel: PhotoPickerViewModel,
    labels: PhotoPickerLabels,
    confirmationError: String? = null,
    onImageConfirmed: (suspend (SelectedPhoto) -> Unit)? = null,
) {
  val state by viewModel.state.collectAsState()
  if (!state.visible) return
  val context = LocalContext.current
  var denied by rememberSaveable { mutableStateOf(false) }
  var waitingForSettings by rememberSaveable { mutableStateOf(false) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) viewModel.showCamera()
      }
  val openGallery =
      rememberPhotoGalleryLauncher(
          onResult = { viewModel.select(it.toString()) }, onFailure = viewModel::reportFailure)
  DisposableEffect(lifecycle, waitingForSettings) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && waitingForSettings) {
        waitingForSettings = false
        if (context.hasCameraPermission()) {
          denied = false
          viewModel.showCamera()
        }
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  Dialog(
      onDismissRequest = viewModel::closePreview,
      properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
              Modifier.fillMaxSize()
                  .safeDrawingPadding()
                  .padding(Dimens.spacingL)
                  .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
                Text(stringResource(labels.title), style = MaterialTheme.typography.headlineSmall)
                if (state.busy)
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.LOADING))
                if (state.failed)
                    Text(
                        when {
                          state.confirmationFailed && confirmationError != null -> confirmationError
                          state.galleryImportRejection ==
                              PhotoImportRejection.PERSONAL_REQUIREMENTS ->
                              stringResource(R.string.personal_photo_invalid)
                          state.galleryImportRejection == PhotoImportRejection.PIXEL_LIMIT ->
                              stringResource(
                                  R.string.photo_picker_pixel_limit,
                                  MAX_INPUT_PHOTO_PIXELS / 1_000_000)
                          else -> stringResource(R.string.photo_picker_failed)
                        },
                        color = MaterialTheme.colorScheme.error)
                when (state.page) {
                  PhotoPage.CAMERA ->
                      CameraCapture(
                          enabled = !state.busy,
                          lens = state.lens,
                          onSwitchLens = viewModel::switchLens,
                          onCapture = viewModel::capture,
                          onFailure = {
                            viewModel.showSources()
                            viewModel.reportFailure()
                          })
                  PhotoPage.PREVIEW -> {
                    AsyncImage(
                        model =
                            ImageRequest.Builder(context)
                                .data(state.selection?.uri)
                                .size(PREVIEW_MAX_PIXELS)
                                .build(),
                        contentDescription = stringResource(labels.previewDescription),
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(PhotoFrameHeight)
                                .testTag(PhotoPickerTestTags.PREVIEW),
                        onError = { viewModel.reportFailure() })
                    Button(
                        onClick = { viewModel.confirm(onImageConfirmed) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.CONFIRM)) {
                          Text(stringResource(R.string.photo_picker_confirm))
                        }
                  }
                  PhotoPage.SOURCE -> Text(stringResource(labels.sourceDescription))
                }
                if (denied) {
                  Text(
                      stringResource(R.string.photo_picker_camera_denied),
                      color = MaterialTheme.colorScheme.error)
                  val activity = context.findActivity()
                  if (activity != null &&
                      !ActivityCompat.shouldShowRequestPermissionRationale(
                          activity, Manifest.permission.CAMERA)) {
                    TextButton(
                        onClick = {
                          try {
                            waitingForSettings = true
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")))
                          } catch (_: Exception) {
                            waitingForSettings = false
                            viewModel.reportFailure()
                          }
                        }) {
                          Text(stringResource(R.string.photo_picker_camera_settings))
                        }
                  }
                }
                if (state.page != PhotoPage.CAMERA)
                    OutlinedButton(
                        onClick = {
                          if (context.hasCameraPermission()) viewModel.showCamera()
                          else
                              try {
                                permission.launch(Manifest.permission.CAMERA)
                              } catch (_: Exception) {
                                viewModel.reportFailure()
                              }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.CAMERA)) {
                          Text(stringResource(R.string.photo_picker_camera))
                        }
                OutlinedButton(
                    onClick = openGallery,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.GALLERY)) {
                      Text(stringResource(R.string.photo_picker_gallery))
                    }
                TextButton(
                    onClick = viewModel::closePreview,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(PhotoPickerTestTags.CANCEL)) {
                      Text(stringResource(R.string.cancel))
                    }
              }
        }
      }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

/** Keep the existing values for UI automation that addresses the picker by tag. */
object PhotoPickerTestTags {
  const val VIEWFINDER = "barcode_photo_viewfinder"
  const val LOADING = "barcode_photo_loading"
  const val PREVIEW = "barcode_photo_preview"
  const val CONFIRM = "barcode_photo_confirm"
  const val CAMERA = "barcode_photo_camera"
  const val GALLERY = "barcode_photo_gallery"
  const val CAPTURE = "barcode_photo_capture"
  /** Switches between the back and front camera; shown only when the device has both. */
  const val SWITCH_CAMERA = "photo_picker_switch_camera"
  const val CANCEL = "barcode_photo_cancel"
}

private const val PREVIEW_MAX_PIXELS = 1280
internal val PhotoFrameHeight = 300.dp
