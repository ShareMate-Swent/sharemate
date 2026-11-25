// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppBackHeader
import com.android.sharemate.ui.components.AppBanner
import com.android.sharemate.ui.components.AppBannerTone
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.components.AppSecondaryButton
import com.android.sharemate.ui.components.ScrollingColumn
import com.android.sharemate.ui.profile.ProfileAvatar
import com.android.sharemate.ui.profile.ProfilePhotoError
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [EditProfileScreen]. */
object EditProfileTestTags {
  /** Returns to Settings. */
  const val BACK = "edit_profile_back"

  /** The profile photo, when the account has one. */
  const val PHOTO = "edit_profile_photo"

  /** The placeholder avatar, when the account has no photo. */
  const val PLACEHOLDER = "edit_profile_placeholder"

  /** Opens the camera and gallery dialog; the whole photo is the touch target. */
  const val CHANGE_PHOTO = "edit_profile_change_photo"

  /** The progress ring shown over the photo while it uploads. */
  const val PHOTO_UPLOADING = "edit_profile_photo_uploading"

  /** The message explaining why the photo could not be changed. */
  const val PHOTO_ERROR = "edit_profile_photo_error"

  /** Uploads the photo of a failed upload again. */
  const val PHOTO_RETRY = "edit_profile_photo_retry"

  /** The text field of the pseudo. */
  const val PSEUDO_FIELD = "edit_profile_pseudo_field"

  /** The "Loading pseudo" text shown while the saved pseudo is read. */
  const val PSEUDO_LOADING = "edit_profile_pseudo_loading"

  /** The message explaining why the pseudo could not be loaded or saved. */
  const val PSEUDO_ERROR = "edit_profile_pseudo_error"

  /** Loads the saved pseudo again after a load failure. */
  const val PSEUDO_RETRY = "edit_profile_pseudo_retry"

  /** Saves the edited pseudo. */
  const val PSEUDO_SAVE = "edit_profile_pseudo_save"

  /** The progress indicator shown in the save button while the pseudo is saved. */
  const val PSEUDO_SAVING = "edit_profile_pseudo_saving"
}

/**
 * The page opened from Settings to change the profile photo and the public pseudo.
 *
 * @param state Photo, pseudo and their loading and error states from [EditProfileViewModel].
 * @param onPseudoChanged Updates the pseudo being edited.
 * @param onSavePseudo Saves the edited pseudo as the public profile name.
 * @param onRetryLoadPseudo Loads the saved pseudo again after a load failure.
 * @param onChangePhoto Opens the camera/gallery dialog to change the profile photo.
 * @param onRetryPhotoUpload Uploads the photo of a failed upload again.
 * @param onBack Returns to Settings.
 */
@Composable
fun EditProfileScreen(
    state: EditProfileUiState,
    onPseudoChanged: (String) -> Unit,
    onSavePseudo: () -> Unit,
    onRetryLoadPseudo: () -> Unit,
    onChangePhoto: () -> Unit,
    onRetryPhotoUpload: () -> Unit,
    onBack: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding),
    ) {
      ScrollingColumn(verticalArrangement = Arrangement.spacedBy(Dimens.spacingL)) {
        AppBackHeader(
            title = stringResource(R.string.edit_profile_title),
            onBack = onBack,
            backTestTag = EditProfileTestTags.BACK,
        )
        PhotoSection(state, onChangePhoto, onRetryPhotoUpload)
        PseudoSection(state, onPseudoChanged, onRetryLoadPseudo)
        Spacer(Modifier.weight(1f))
        AppPrimaryButton(
            text = stringResource(R.string.settings_pseudo_save),
            onClick = onSavePseudo,
            enabled = state.canSavePseudo,
            loading = state.isSavingPseudo,
            loadingTestTag = EditProfileTestTags.PSEUDO_SAVING,
            modifier = Modifier.testTag(EditProfileTestTags.PSEUDO_SAVE),
        )
      }
    }
  }
}

/**
 * The profile photo with a camera badge. Tapping it changes the photo; an upload shows a progress
 * ring over it, and a failure shows its message with Retry after a failed upload.
 */
@Composable
private fun PhotoSection(
    state: EditProfileUiState,
    onChangePhoto: () -> Unit,
    onRetryPhotoUpload: () -> Unit,
) {
  val changePhoto = stringResource(R.string.settings_change_photo)
  Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
  ) {
    Box(contentAlignment = Alignment.Center) {
      ProfileAvatar(
          photoUrl = state.photoUrl,
          size = SettingsLayout.editAvatarSize,
          iconSize = SettingsLayout.editAvatarIconSize,
          contentDescription = stringResource(R.string.settings_profile_photo),
          photoTestTag = EditProfileTestTags.PHOTO,
          placeholderTestTag = EditProfileTestTags.PLACEHOLDER,
      )
      // A sibling of the avatar, so the photo keeps its own semantics and test tags.
      Box(
          Modifier.matchParentSize()
              .clip(CircleShape)
              .clickable(
                  enabled = !state.isUploadingPhoto, role = Role.Button, onClick = onChangePhoto)
              .semantics { contentDescription = changePhoto }
              .testTag(EditProfileTestTags.CHANGE_PHOTO))
      if (state.isUploadingPhoto) {
        CircularProgressIndicator(
            modifier =
                Modifier.size(SettingsLayout.editAvatarSize)
                    .testTag(EditProfileTestTags.PHOTO_UPLOADING))
      } else {
        CameraBadge(Modifier.align(Alignment.BottomEnd))
      }
    }
    Text(
        text = stringResource(R.string.edit_profile_photo_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.photoError?.let { error -> PhotoError(error, onRetryPhotoUpload) }
  }
}

/** The round badge on the photo that shows it can be changed. */
@Composable
private fun CameraBadge(modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .size(SettingsLayout.editBadgeSize)
              .background(MaterialTheme.colorScheme.primary, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = Icons.Filled.PhotoCamera,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(SettingsLayout.editBadgeIconSize),
    )
  }
}

/** Why the photo could not be changed, with Retry after a failed upload. */
@Composable
private fun PhotoError(error: ProfilePhotoError, onRetry: () -> Unit) {
  AppBanner(
      message =
          stringResource(
              when (error) {
                ProfilePhotoError.INVALID_PHOTO -> R.string.personal_photo_invalid
                ProfilePhotoError.UPLOAD_FAILED -> R.string.settings_photo_upload_failed
              }),
      tone = AppBannerTone.ERROR,
      testTag = EditProfileTestTags.PHOTO_ERROR,
  )
  if (error == ProfilePhotoError.UPLOAD_FAILED) {
    TextButton(onClick = onRetry, modifier = Modifier.testTag(EditProfileTestTags.PHOTO_RETRY)) {
      Text(stringResource(R.string.profile_photo_retry))
    }
  }
}

/** The pseudo field with its label, and the loading or error state under it. */
@Composable
private fun PseudoSection(
    state: EditProfileUiState,
    onPseudoChanged: (String) -> Unit,
    onRetryLoadPseudo: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val loadFailed = state.pseudoError == EditProfilePseudoError.LOAD_FAILED
  Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
    Text(
        text = stringResource(R.string.settings_pseudo_label),
        style = MaterialTheme.typography.labelMedium,
    )
    OutlinedTextField(
        value = state.pseudo,
        onValueChange = onPseudoChanged,
        enabled = !state.isLoadingPseudo && !state.isSavingPseudo && !loadFailed,
        isError =
            state.pseudoError == EditProfilePseudoError.PSEUDO_TAKEN ||
                state.pseudoError == EditProfilePseudoError.SAVE_FAILED,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outlineVariant,
            ),
        modifier = Modifier.fillMaxWidth().testTag(EditProfileTestTags.PSEUDO_FIELD),
    )
    if (state.isLoadingPseudo) {
      Text(
          text = stringResource(R.string.settings_pseudo_loading),
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.testTag(EditProfileTestTags.PSEUDO_LOADING),
      )
    }
  }
  state.pseudoError?.let { error ->
    AppBanner(
        message =
            stringResource(
                when (error) {
                  EditProfilePseudoError.LOAD_FAILED -> R.string.settings_pseudo_load_failed
                  EditProfilePseudoError.SAVE_FAILED -> R.string.settings_pseudo_save_failed
                  EditProfilePseudoError.PSEUDO_TAKEN -> R.string.auth_error_pseudo_taken
                }),
        tone = AppBannerTone.ERROR,
        testTag = EditProfileTestTags.PSEUDO_ERROR,
    )
  }
  if (loadFailed) {
    AppSecondaryButton(
        text = stringResource(R.string.household_retry),
        onClick = onRetryLoadPseudo,
        modifier = Modifier.testTag(EditProfileTestTags.PSEUDO_RETRY),
    )
  }
}
