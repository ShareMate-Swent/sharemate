// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
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
  /** The round photo target, which opens the camera/gallery dialog. */
  const val TARGET = "profile_photo_step_target"
  /** The placeholder avatar, present until a photo is shown. */
  const val PLACEHOLDER = "profile_photo_step_placeholder"
  /** Opens the camera to take the photo. */
  const val TAKE_PHOTO = "profile_photo_step_take_photo"
  /** Opens the gallery to pick the photo. */
  const val FROM_GALLERY = "profile_photo_step_from_gallery"
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
 * @param camera The camera/gallery dialog, used for camera captures and from the photo target.
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
  val context = LocalContext.current
  val openCamera = {
    camera.open()
    camera.showCamera()
  }
  // Without the permission the dialog opens on its sources, which explains the refusal on retry.
  val cameraPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else camera.open()
      }
  val gallery =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.selectFromGallery(it.toString()) }
      }
  ProfilePhotoStepContent(
      state = state,
      onAddPhoto = camera::open,
      onTakePhoto = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
            openCamera()
        else
            runCatching { cameraPermission.launch(Manifest.permission.CAMERA) }
                .onFailure { camera.open() }
      },
      onPickFromGallery = {
        runCatching {
              gallery.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            .onFailure { camera.open() }
      },
      onSave = viewModel::save,
      onSkip = viewModel::skip,
  )
  ProfilePhotoPicker(onPhotoConfirmed = viewModel::select, camera = camera)
}

/**
 * Stateless content of the profile photo step: "Skip" at the top, the title and a round photo
 * target in the middle, and "Save" at the bottom, disabled until a photo is picked. The middle
 * scrolls on short screens so "Skip" and "Save" always stay reachable.
 *
 * @param state What to show.
 * @param onAddPhoto Opens the camera/gallery dialog, from the photo target.
 * @param onTakePhoto Opens the camera.
 * @param onPickFromGallery Opens the gallery.
 * @param onSave Uploads the picked photo; also used to retry a failed upload.
 * @param onSkip Ends the step without a photo, also while uploading.
 */
@Composable
internal fun ProfilePhotoStepContent(
    state: ProfilePhotoUiState,
    onAddPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val enabled = !state.isUploading
  val addLabel =
      stringResource(
          if (state.photo == null) R.string.profile_photo_add else R.string.profile_photo_change)
  Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding)
                .testTag(ProfilePhotoStepTestTags.SCREEN),
    ) {
      // Skip stays enabled while uploading, so an offline user is never stuck here.
      TextButton(
          onClick = onSkip,
          modifier = Modifier.align(Alignment.End).testTag(ProfilePhotoStepTestTags.SKIP),
      ) {
        Text(
            stringResource(R.string.profile_photo_skip),
            style = MaterialTheme.typography.titleSmall)
      }
      Column(
          modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
      ) {
        Text(
            text = stringResource(R.string.profile_photo_step_title),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.profile_photo_step_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhotoTarget(
            photoUrl = state.photo?.let { Uri.fromFile(it).toString() },
            onClick = onAddPhoto,
            onClickLabel = addLabel,
            enabled = enabled,
            modifier =
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = Dimens.spacingL),
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
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.testTag(ProfilePhotoStepTestTags.ERROR),
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
          SourceCard(
              icon = Icons.Outlined.PhotoCamera,
              label = stringResource(R.string.profile_photo_take),
              onClick = onTakePhoto,
              enabled = enabled,
              modifier = Modifier.weight(1f).testTag(ProfilePhotoStepTestTags.TAKE_PHOTO))
          SourceCard(
              icon = Icons.Outlined.Image,
              label = stringResource(R.string.profile_photo_from_gallery),
              onClick = onPickFromGallery,
              enabled = enabled,
              modifier = Modifier.weight(1f).testTag(ProfilePhotoStepTestTags.FROM_GALLERY))
        }
      }
      val retry = state.error == ProfilePhotoError.UPLOAD_FAILED
      Button(
          onClick = onSave,
          enabled = enabled && state.photo != null,
          shape = MaterialTheme.shapes.medium,
          modifier =
              Modifier.fillMaxWidth()
                  .padding(top = Dimens.spacingM)
                  .heightIn(min = Dimens.buttonHeight)
                  .testTag(
                      if (retry) ProfilePhotoStepTestTags.RETRY else ProfilePhotoStepTestTags.SAVE),
      ) {
        Text(
            stringResource(
                if (retry) R.string.profile_photo_retry else R.string.profile_photo_save))
      }
    }
  }
}

/**
 * The avatar preview inside a dashed ring, with a "+" badge. Tapping it opens the camera/gallery
 * dialog, like the add photo button.
 */
@Composable
private fun PhotoTarget(
    photoUrl: String?,
    onClick: () -> Unit,
    onClickLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  val ringColor =
      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ProfileLayout.STEP_AVATAR_RING_ALPHA)
  Box(modifier.size(ProfileLayout.stepAvatarSize)) {
    Box(
        Modifier.matchParentSize()
            .testTag(ProfilePhotoStepTestTags.TARGET)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick)
            .drawBehind {
              val width = ProfileLayout.stepAvatarRingWidth.toPx()
              val dash = ProfileLayout.stepAvatarDashLength.toPx()
              drawCircle(
                  color = ringColor,
                  radius = (size.minDimension - width) / 2,
                  style =
                      Stroke(
                          width = width,
                          pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))))
            }) {
          ProfileAvatar(
              photoUrl = photoUrl,
              size = ProfileLayout.stepAvatarSize,
              iconSize = ProfileLayout.stepAvatarIconSize,
              contentDescription = stringResource(R.string.profile_photo_preview),
              photoTestTag = ProfilePhotoStepTestTags.PHOTO,
              placeholderTestTag = ProfilePhotoStepTestTags.PLACEHOLDER,
              containerColor = MaterialTheme.colorScheme.surface,
              iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
              icon = Icons.Outlined.Person,
              modifier = Modifier.padding(ProfileLayout.stepAvatarRingWidth),
          )
        }
    // Sits on the ring at the bottom right, outside the clipped circle so it is not cut off.
    Box(
        Modifier.align(Alignment.BottomEnd)
            .size(ProfileLayout.stepBadgeSize)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center) {
          Icon(
              Icons.Filled.Add,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimary)
        }
  }
}

/** A card with an icon above its [label], offering one way to get the photo. */
@Composable
private fun SourceCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  Card(
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.large,
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surface,
              contentColor = MaterialTheme.colorScheme.onSurface),
      modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Dimens.spacingL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
              Icon(
                  icon,
                  contentDescription = null,
                  modifier = Modifier.size(ProfileLayout.sourceIconSize))
              Text(label, style = MaterialTheme.typography.titleSmall)
            }
      }
}
