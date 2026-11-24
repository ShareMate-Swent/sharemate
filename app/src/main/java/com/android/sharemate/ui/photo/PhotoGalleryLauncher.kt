// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.photo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/** Only one system picker can be pending, even before the next recomposition. */
@Composable
internal fun rememberPhotoGalleryLauncher(
    onResult: (Uri) -> Unit,
    onFailure: () -> Unit,
): () -> Unit {
  val pending = rememberSaveable { mutableStateOf(false) }
  val gallery =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pending.value = false
        uri?.let(onResult)
      }
  return launch@{
    if (pending.value) return@launch
    pending.value = true
    try {
      gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    } catch (_: Exception) {
      pending.value = false
      onFailure()
    }
  }
}
