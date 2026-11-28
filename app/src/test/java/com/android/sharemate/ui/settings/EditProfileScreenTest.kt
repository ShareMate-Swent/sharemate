// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.ui.profile.ProfilePhotoError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the Edit profile page: the photo, the pseudo and their loading and error states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditProfileScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var pseudoSaves = 0
  private var pseudoRetries = 0
  private var editedPseudo = ""
  private var photoChanges = 0
  private var photoRetries = 0
  private var backs = 0
  private var state by mutableStateOf(EditProfileUiState())

  private fun show(initial: EditProfileUiState = EditProfileUiState()) {
    state = initial
    composeTestRule.setContent {
      MaterialTheme {
        EditProfileScreen(
            state = state,
            onPseudoChanged = {
              editedPseudo = it
              state = state.copy(pseudo = it)
            },
            onSavePseudo = { pseudoSaves++ },
            onRetryLoadPseudo = { pseudoRetries++ },
            onChangePhoto = { photoChanges++ },
            onRetryPhotoUpload = { photoRetries++ },
            onBack = { backs++ })
      }
    }
  }

  private fun text(id: Int) = composeTestRule.activity.getString(id)

  @Test
  fun theBackButtonReturnsToSettingsAndTheTitleNamesThePage() {
    show()

    composeTestRule.onNodeWithText(text(R.string.edit_profile_title)).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(EditProfileTestTags.BACK)
        .assertContentDescriptionEquals(text(R.string.edit_profile_back))
        .performClick()

    assertEquals(1, backs)
  }

  @Test
  fun tappingThePhotoChangesItAndUploadingDisablesIt() {
    show()

    val photo = composeTestRule.onNodeWithTag(EditProfileTestTags.CHANGE_PHOTO)
    photo.assertContentDescriptionEquals(text(R.string.settings_change_photo)).performClick()
    assertEquals(1, photoChanges)
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO_UPLOADING).assertDoesNotExist()

    composeTestRule.runOnIdle { state = state.copy(isUploadingPhoto = true) }
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO_UPLOADING).assertIsDisplayed()
    photo.assertIsNotEnabled()
  }

  @Test
  fun aPlaceholderShowsWithoutAPhotoAndThePhotoIsDrawnOnceThereIsAUrl() {
    show()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PLACEHOLDER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO).assertDoesNotExist()

    composeTestRule.runOnIdle {
      state = state.copy(photoUrl = "content://sharemate.test/missing.png")
    }
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO).assertExists()
  }

  @Test
  fun aFailedUploadShowsItsMessageAndOffersRetry() {
    show(EditProfileUiState(photoError = ProfilePhotoError.UPLOAD_FAILED))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PHOTO_ERROR)
        .assertTextEquals(text(R.string.settings_photo_upload_failed))
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO_RETRY).performClick()
    assertEquals(1, photoRetries)
  }

  @Test
  fun anInvalidPhotoShowsItsMessageWithoutRetry() {
    show(EditProfileUiState(photoError = ProfilePhotoError.INVALID_PHOTO))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PHOTO_ERROR)
        .assertTextEquals(text(R.string.personal_photo_invalid))
    composeTestRule.onNodeWithTag(EditProfileTestTags.PHOTO_RETRY).assertDoesNotExist()
  }

  @Test
  fun thePseudoCanBeEditedAndSaved() {
    show(EditProfileUiState(pseudo = "Alex", savedPseudo = "Alex"))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD)
        .assert(hasText("Alex"))
        .performTextClearance()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).performTextInput("Sam")
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsEnabled().performClick()

    assertEquals("Sam", editedPseudo)
    assertEquals(1, pseudoSaves)
  }

  @Test
  fun anUnchangedPseudoCannotBeSaved() {
    show(EditProfileUiState(pseudo = "Alex", savedPseudo = "Alex"))

    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsNotEnabled()
  }

  @Test
  fun whileThePseudoLoadsTheFieldIsLockedAndALoadingTextShows() {
    show(EditProfileUiState(isLoadingPseudo = true))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_LOADING)
        .assertTextEquals(text(R.string.settings_pseudo_loading))
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsNotEnabled()
  }

  @Test
  fun aLoadFailureOffersRetryAndBlocksEditing() {
    show(EditProfileUiState(pseudo = "Alex", pseudoError = EditProfilePseudoError.LOAD_FAILED))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_ERROR)
        .assertTextEquals(text(R.string.settings_pseudo_load_failed))
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_RETRY).performClick()
    assertEquals(1, pseudoRetries)
  }

  @Test
  fun aSaveFailureShowsItsMessageAndKeepsTheFieldEditable() {
    show(
        EditProfileUiState(
            pseudo = "Sam", savedPseudo = "Alex", pseudoError = EditProfilePseudoError.SAVE_FAILED))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_ERROR)
        .assertTextEquals(text(R.string.settings_pseudo_save_failed))
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsEnabled()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_RETRY).assertDoesNotExist()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsEnabled()
  }

  @Test
  fun aTakenPseudoShowsItsSpecificMessage() {
    show(
        EditProfileUiState(
            pseudo = "Sam",
            savedPseudo = "Alex",
            pseudoError = EditProfilePseudoError.PSEUDO_TAKEN))

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_ERROR)
        .assertTextEquals(text(R.string.auth_error_pseudo_taken))
  }

  @Test
  fun whileSavingTheButtonShowsProgressAndIsDisabled() {
    show(EditProfileUiState(pseudo = "Sam", savedPseudo = "Alex", isSavingPseudo = true))

    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVING).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsNotEnabled()
  }

  @Test
  fun everyActionStaysReachableOnASmallScreen() {
    state = EditProfileUiState(pseudo = "Sam", savedPseudo = "Alex")
    composeTestRule.setContent {
      MaterialTheme {
        Box(Modifier.height(SMALL_SCREEN_HEIGHT)) {
          EditProfileScreen(
              state = state,
              onPseudoChanged = {},
              onSavePseudo = { pseudoSaves++ },
              onRetryLoadPseudo = {},
              onChangePhoto = {},
              onRetryPhotoUpload = {},
              onBack = {})
        }
      }
    }

    composeTestRule
        .onNodeWithTag(EditProfileTestTags.PSEUDO_SAVE)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()

    assertEquals(1, pseudoSaves)
  }

  private companion object {
    val SMALL_SCREEN_HEIGHT = 240.dp
  }
}
