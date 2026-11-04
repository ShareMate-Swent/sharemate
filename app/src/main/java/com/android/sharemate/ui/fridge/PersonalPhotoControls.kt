// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.model.image.PersonalPhotoDraftError

/** Gallery choice for the item's illustration, independent of barcode recognition. */
@Composable
internal fun PersonalPhotoPicker(controller: PersonalPhotoDraftController, enabled: Boolean) {
  val state by controller.state.collectAsState()
  var launchedGeneration by remember { mutableStateOf(0L) }
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (controller.selectionGeneration == launchedGeneration) controller.select(uri?.toString())
      }
  Column {
    OutlinedButton(
        onClick = {
          launchedGeneration = controller.selectionGeneration
          picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        enabled = enabled,
        modifier = Modifier.testTag("personal_photo_choose")) {
          Text(
              stringResource(
                  if (state.draft == null) R.string.personal_photo_choose
                  else R.string.personal_photo_change))
        }
    state.draft?.let { file ->
      AsyncImage(
          model =
              ImageRequest.Builder(LocalContext.current)
                  .data(file)
                  .size(512)
                  .memoryCachePolicy(CachePolicy.DISABLED)
                  .diskCachePolicy(CachePolicy.DISABLED)
                  .build(),
          contentDescription = stringResource(R.string.personal_photo_preview),
          modifier = Modifier.fillMaxWidth().height(120.dp).testTag("personal_photo_preview"))
      TextButton(
          onClick = controller::discardDraft,
          enabled = enabled,
          modifier = Modifier.testTag("personal_photo_remove")) {
            Text(stringResource(R.string.personal_photo_remove))
          }
    }
    if (state.importing) LinearProgressIndicator(Modifier.testTag("personal_photo_importing"))
    state.selectionError?.let { error ->
      Text(
          stringResource(
              when (error) {
                PersonalPhotoDraftError.TOO_LARGE -> R.string.personal_photo_too_large
                PersonalPhotoDraftError.UNSUPPORTED -> R.string.personal_photo_unsupported
                PersonalPhotoDraftError.INVALID -> R.string.personal_photo_invalid
                PersonalPhotoDraftError.UNREADABLE -> R.string.personal_photo_unreadable
              }),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("personal_photo_selection_error"))
    }
  }
}
