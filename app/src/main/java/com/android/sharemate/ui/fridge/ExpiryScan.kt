// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.item.MlKitExpiryDateReader
import com.android.sharemate.ui.photo.DiscardPhotoOnDispose
import com.android.sharemate.ui.photo.OnPhotoPickerCancelled
import com.android.sharemate.ui.photo.PhotoPickerDialog
import com.android.sharemate.ui.photo.PhotoPickerLabels
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.findActivity
import com.android.sharemate.ui.photo.photoPickerViewModel

@Composable
internal fun ExpiryScan(
    enabled: Boolean,
    onDateConfirmed: (String) -> Unit,
    scan: ExpiryScanViewModel = expiryScanViewModel(),
    picker: PhotoPickerViewModel = photoPickerViewModel("fridge-expiry-photo"),
) {
  val photo by picker.state.collectAsStateWithLifecycle()
  val candidates by scan.candidates.collectAsStateWithLifecycle()
  val activity = LocalContext.current.findActivity()
  DiscardPhotoOnDispose(picker)
  OnPhotoPickerCancelled(picker, picker::reset)
  DisposableEffect(scan, activity) {
    onDispose { if (activity?.isChangingConfigurations != true) scan.clear() }
  }

  TextButton(
      enabled = enabled && !photo.busy && !(scan.pickerOpened && photo.visible),
      onClick = {
        picker.reset()
        scan.openPicker()
        picker.open()
      }) {
        Text(stringResource(R.string.fridge_scan_expiry))
      }
  // The item draft survives rotation but is not restored after process death.
  if (scan.pickerOpened) {
    PhotoPickerDialog(
        viewModel = picker,
        labels = expiryPhotoLabels,
        confirmationError = stringResource(R.string.fridge_expiry_scan_failed)) { selection ->
          scan.scan(selection.uri)
        }
  }

  LaunchedEffect(photo.confirmed, photo.busy) { if (photo.confirmed && !photo.busy) picker.reset() }
  candidates
      ?.takeIf { !photo.visible }
      ?.let { dates ->
        AlertDialog(
            onDismissRequest = scan::clear,
            title = { Text(stringResource(R.string.fridge_expiry_candidates)) },
            text = {
              Column(Modifier.verticalScroll(rememberScrollState())) {
                if (dates.isEmpty()) {
                  Text(stringResource(R.string.fridge_expiry_no_date))
                }
                for (date in dates) {
                  TextButton(
                      modifier = Modifier.fillMaxWidth(),
                      onClick = {
                        onDateConfirmed(date.toString())
                        scan.clear()
                      }) {
                        Text(date.toString())
                      }
                }
              }
            },
            confirmButton = {
              TextButton(onClick = scan::clear) {
                Text(stringResource(R.string.fridge_expiry_enter_manually))
              }
            })
      }
}

private val expiryPhotoLabels =
    PhotoPickerLabels(
        R.string.fridge_scan_expiry,
        R.string.fridge_expiry_photo_preview,
        R.string.fridge_expiry_photo_sources)

@Composable
private fun expiryScanViewModel(): ExpiryScanViewModel {
  val context = LocalContext.current.applicationContext
  return viewModel(
      key = "fridge-expiry-scan",
      factory =
          viewModelFactory { initializer { ExpiryScanViewModel(MlKitExpiryDateReader(context)) } })
}
