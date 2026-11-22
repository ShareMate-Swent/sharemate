// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.theme.Dimens

/** Test tags of the profile photo step. */
object ProfilePhotoStepTestTags {
  /** The whole step screen. */
  const val SCREEN = "profile_photo_step_screen"
  /** The previewed photo, present once a photo is picked. */
  const val PHOTO = "profile_photo_step_photo"
  /** The placeholder avatar, present until a photo is shown. */
  const val PLACEHOLDER = "profile_photo_step_placeholder"
  /** Opens the camera/gallery dialog to add or change the photo. */
  const val ADD_PHOTO = "profile_photo_step_add_photo"
  /** Uploads the picked photo. */
  const val SAVE = "profile_photo_step_save"
  /** Retries a failed upload. */
  const val RETRY = "profile_photo_step_retry"
  /** Ends the step without a photo. */
  const val SKIP = "profile_photo_step_skip"
  /** Indeterminate progress shown while uploading. */
  const val PROGRESS = "profile_photo_step_progress"
  /** The error message. */
  const val ERROR = "profile_photo_step_error"
}

/**
 * Optional step shown right after an email sign-up to add a profile photo. Back press skips the
 * step, cancelling an upload in progress.
 *
 * @param viewModel Holds the picked photo and uploads it.
 * @param onDone Called once the photo was saved or the step was skipped.
 * @param camera The camera/gallery dialog used to pick the photo.
 */
@Composable
fun ProfilePhotoStepScreen(
    viewModel: ProfilePhotoViewModel,
    onDone: () -> Unit,
    camera: PhotoPickerViewModel = profilePhotoCamera(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.done) { if (state.done) onDone() }
  BackHandler { viewModel.skip() }
  ProfilePhotoStepContent(
      state = state,
      onAddPhoto = camera::open,
      onSave = viewModel::save,
      onSkip = viewModel::skip,
  )
  ProfilePhotoPicker(onPhotoConfirmed = viewModel::select, camera = camera)
}

/**
 * Stateless content of the profile photo step.
 *
 * @param state What to show.
 * @param onAddPhoto Opens the camera/gallery dialog.
 * @param onSave Uploads the picked photo; also used to retry a failed upload.
 * @param onSkip Ends the step without a photo, also while uploading.
 */
@Composable
internal fun ProfilePhotoStepContent(
    state: ProfilePhotoUiState,
    onAddPhoto: () -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val enabled = !state.isUploading
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
    Column(
        modifier =
            // Centred when it fits, scrollable on short screens (landscape, large text).
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding)
                .testTag(ProfilePhotoStepTestTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingM, Alignment.CenterVertically),
    ) {
      Text(
          text = stringResource(R.string.profile_photo_step_title),
          style = MaterialTheme.typography.headlineSmall,
          textAlign = TextAlign.Center,
      )
      Text(
          text = stringResource(R.string.profile_photo_step_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      ProfileAvatar(
          photoUrl = state.photo?.let { Uri.fromFile(it).toString() },
          size = ProfileLayout.stepAvatarSize,
          iconSize = ProfileLayout.stepAvatarIconSize,
          contentDescription = stringResource(R.string.profile_photo_preview),
          photoTestTag = ProfilePhotoStepTestTags.PHOTO,
          placeholderTestTag = ProfilePhotoStepTestTags.PLACEHOLDER,
      )
      if (state.isUploading) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().testTag(ProfilePhotoStepTestTags.PROGRESS))
      }
      state.error?.let { error ->
        Text(
            text =
                stringResource(
                    when (error) {
                      ProfilePhotoError.INVALID_PHOTO -> R.string.personal_photo_invalid
                      ProfilePhotoError.UPLOAD_FAILED -> R.string.profile_photo_upload_failed
                    }),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(ProfilePhotoStepTestTags.ERROR),
        )
      }
      Spacer(Modifier.height(Dimens.spacingXs))
      OutlinedButton(
          onClick = onAddPhoto,
          enabled = enabled,
          modifier =
              Modifier.fillMaxWidth()
                  .height(Dimens.buttonHeight)
                  .testTag(ProfilePhotoStepTestTags.ADD_PHOTO),
      ) {
        Text(
            stringResource(
                if (state.photo == null) R.string.profile_photo_add
                else R.string.profile_photo_change))
      }
      if (state.photo != null) {
        val retry = state.error == ProfilePhotoError.UPLOAD_FAILED
        Button(
            onClick = onSave,
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth()
                    .height(Dimens.buttonHeight)
                    .testTag(
                        if (retry) ProfilePhotoStepTestTags.RETRY
                        else ProfilePhotoStepTestTags.SAVE),
        ) {
          Text(
              stringResource(
                  if (retry) R.string.profile_photo_retry else R.string.profile_photo_save))
        }
      }
      // Skip stays enabled while uploading, so an offline user is never stuck here.
      TextButton(
          onClick = onSkip,
          modifier = Modifier.testTag(ProfilePhotoStepTestTags.SKIP),
      ) {
        Text(stringResource(R.string.profile_photo_skip))
      }
    }
  }
}
