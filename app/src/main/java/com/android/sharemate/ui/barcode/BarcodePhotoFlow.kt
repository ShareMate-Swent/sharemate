package com.android.sharemate.ui.barcode

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

/** Captures/selects an image only; #139 supplies the optional recognition consumer. */
@Composable
fun BarcodePhotoFlow(
    viewModel: BarcodePhotoViewModel,
    onImageConfirmed: (suspend (String) -> Unit)? = null,
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
      onDismissRequest = viewModel::closePreview,
      properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
              Modifier.fillMaxSize()
                  .safeDrawingPadding()
                  .padding(24.dp)
                  .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.barcode_photo_title),
                    style = MaterialTheme.typography.headlineSmall)
                if (state.busy)
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("barcode_photo_loading"))
                if (state.failed)
                    Text(
                        stringResource(R.string.barcode_photo_failed),
                        color = MaterialTheme.colorScheme.error)
                when (state.page) {
                  PhotoPage.CAMERA ->
                      BarcodeCamera(
                          enabled = !state.busy,
                          onCapture = viewModel::capture,
                          onFailure = {
                            viewModel.showSources()
                            viewModel.reportFailure()
                          })
                  PhotoPage.PREVIEW -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(state.uri).size(1280).build(),
                        contentDescription = stringResource(R.string.barcode_photo_preview),
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxWidth().height(300.dp).testTag("barcode_photo_preview"),
                        onError = { viewModel.reportFailure() })
                    Button(
                        onClick = { viewModel.confirm(onImageConfirmed) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("barcode_photo_confirm")) {
                          Text(stringResource(R.string.barcode_photo_confirm))
                        }
                  }
                  PhotoPage.SOURCE -> Text(stringResource(R.string.barcode_photo_choose))
                }
                if (denied) {
                  Text(
                      stringResource(R.string.barcode_camera_denied),
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
                          Text(stringResource(R.string.barcode_camera_settings))
                        }
                  }
                }
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
                    modifier = Modifier.fillMaxWidth().testTag("barcode_photo_camera")) {
                      Text(stringResource(R.string.barcode_photo_camera))
                    }
                OutlinedButton(
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
                    modifier = Modifier.fillMaxWidth().testTag("barcode_photo_gallery")) {
                      Text(stringResource(R.string.barcode_photo_gallery))
                    }
                TextButton(
                    onClick = viewModel::closePreview,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("barcode_photo_cancel")) {
                      Text(stringResource(R.string.fridge_cancel))
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
