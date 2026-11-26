// Co-authored-by: OpenAI Codex <noreply@openai.com>
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.android.sharemate.ui.receipts.ReceiptExitDialog
import com.android.sharemate.ui.receipts.ReceiptHeader
import com.android.sharemate.ui.receipts.ReceiptProgress
import com.android.sharemate.ui.theme.Dimens

data class PhotoPickerLabels(
    @StringRes val title: Int,
    @StringRes val previewDescription: Int,
    @StringRes val sourceDescription: Int,
    val receiptProgress: Boolean = false,
)

/** Captures or imports a photo and waits for the caller to finish using the confirmed selection. */
@Composable
fun PhotoPickerDialog(
    viewModel: PhotoPickerViewModel,
    labels: PhotoPickerLabels,
    confirmationError: String? = null,
    onSaveDraft: (suspend (SelectedPhoto) -> Unit)? = null,
    onImageConfirmed: (suspend (SelectedPhoto) -> Unit)? = null,
) {
  val state by viewModel.state.collectAsState()
  if (!state.visible) return
  val context = LocalContext.current
  var denied by rememberSaveable { mutableStateOf(false) }
  var showExitChoice by rememberSaveable { mutableStateOf(false) }
  val requestClose = {
    if (!state.busy) {
      if (onSaveDraft != null && state.selection != null && !state.confirmed) showExitChoice = true
      else viewModel.closePreview()
    }
  }
  var waitingForSettings by rememberSaveable { mutableStateOf(false) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) viewModel.showCamera()
      }
  val gallery =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        viewModel.select(it?.toString())
      }
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
      onDismissRequest = requestClose,
      properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color =
                if (labels.receiptProgress) MaterialTheme.colorScheme.background
                else MaterialTheme.colorScheme.surface) {
              Column(
                  Modifier.fillMaxSize()
                      .safeDrawingPadding()
                      .padding(Dimens.spacingL)
                      .verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
                    if (labels.receiptProgress) {
                      ReceiptHeader(stringResource(labels.title), onBack = requestClose)
                      ReceiptProgress(0)
                    } else
                        Text(
                            stringResource(labels.title),
                            style = MaterialTheme.typography.headlineSmall)
                    if (state.busy)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.LOADING))
                    if (state.failed)
                        Text(
                            confirmationError.takeIf { state.confirmationFailed }
                                ?: stringResource(R.string.photo_picker_failed),
                            color = MaterialTheme.colorScheme.error)
                    when (state.page) {
                      PhotoPage.CAMERA ->
                          CameraCapture(
                              enabled = !state.busy,
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
                            modifier =
                                Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.CONFIRM)) {
                              Text(stringResource(R.string.photo_picker_confirm))
                            }
                      }
                      PhotoPage.SOURCE ->
                          if (!labels.receiptProgress)
                              Text(stringResource(labels.sourceDescription))
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
                            shape = RoundedCornerShape(14.dp),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface),
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
                            modifier =
                                Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.CAMERA)) {
                              if (labels.receiptProgress) ReceiptSourceLabel(true)
                              else Text(stringResource(R.string.photo_picker_camera))
                            }
                    OutlinedButton(
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface),
                        onClick = {
                          try {
                            gallery.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly))
                          } catch (_: Exception) {
                            viewModel.reportFailure()
                          }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(PhotoPickerTestTags.GALLERY)) {
                          if (labels.receiptProgress) ReceiptSourceLabel(false)
                          else Text(stringResource(R.string.photo_picker_gallery))
                        }
                    if (!labels.receiptProgress)
                        TextButton(
                            onClick = viewModel::closePreview,
                            enabled = !state.busy,
                            modifier = Modifier.testTag(PhotoPickerTestTags.CANCEL)) {
                              Text(stringResource(R.string.cancel))
                            }
                  }
            }
      }
  if (showExitChoice) {
    ReceiptExitDialog(
        busy = state.busy,
        failed = state.failed,
        onSaveDraft = { viewModel.confirm(onSaveDraft) },
        onDiscard = {
          showExitChoice = false
          viewModel.reset()
        },
        onDismiss = { showExitChoice = false })
  }
}

@Composable
private fun ReceiptSourceLabel(camera: Boolean) {
  Row(
      Modifier.fillMaxWidth().padding(vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
          Icon(
              if (camera) Icons.Outlined.CameraAlt else Icons.Outlined.PhotoLibrary,
              null,
              Modifier.padding(10.dp).size(24.dp),
              tint = MaterialTheme.colorScheme.primary)
        }
        Column {
          Text(
              stringResource(
                  if (camera) R.string.receipt_take_photo else R.string.receipt_choose_gallery),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface)
          Text(
              stringResource(
                  if (camera) R.string.receipt_camera_hint else R.string.receipt_gallery_hint),
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
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
  const val CANCEL = "barcode_photo_cancel"
}

private const val PREVIEW_MAX_PIXELS = 1280
internal val PhotoFrameHeight = 300.dp
