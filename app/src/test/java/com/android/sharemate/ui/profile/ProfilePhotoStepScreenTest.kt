// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.R
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks which actions the profile photo step offers in each state, that they reach their
 * callbacks, that a short screen keeps Skip and Save in view and scrolls to the rest, and that
 * system back skips the step, also while uploading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ProfilePhotoStepScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var state by mutableStateOf(ProfilePhotoUiState())
  private val calls = mutableListOf<String>()

  private fun setContent() =
      compose.setContent {
        MaterialTheme {
          ProfilePhotoStepContent(
              state = state,
              onAddPhoto = { calls += "add" },
              onTakePhoto = { calls += "camera" },
              onPickFromGallery = { calls += "gallery" },
              onSave = { calls += "save" },
              onSkip = { calls += "skip" })
        }
      }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  /** The avatar sits inside the clickable photo target, which merges its children. */
  private fun avatarNode(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  private fun text(id: Int) = compose.activity.getString(id)

  private fun hasClickLabel(label: String) =
      SemanticsMatcher("click label is '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
      }

  @Test
  fun initialStepOffersCameraGalleryAndSkipWithSaveDisabled() {
    setContent()
    node(ProfilePhotoStepTestTags.TAKE_PHOTO).assertTextEquals(text(R.string.profile_photo_take))
    node(ProfilePhotoStepTestTags.FROM_GALLERY)
        .assertTextEquals(text(R.string.profile_photo_from_gallery))
    node(ProfilePhotoStepTestTags.TARGET).assert(hasClickLabel(text(R.string.profile_photo_add)))
    avatarNode(ProfilePhotoStepTestTags.PLACEHOLDER).assertExists()
    node(ProfilePhotoStepTestTags.SAVE).assertIsNotEnabled()
    for (tag in
        listOf(
            ProfilePhotoStepTestTags.RETRY,
            ProfilePhotoStepTestTags.PROGRESS,
            ProfilePhotoStepTestTags.ERROR)) {
      node(tag).assertDoesNotExist()
    }
    node(ProfilePhotoStepTestTags.TAKE_PHOTO).performClick()
    node(ProfilePhotoStepTestTags.FROM_GALLERY).performClick()
    node(ProfilePhotoStepTestTags.SKIP).performClick()
    assertEquals(listOf("camera", "gallery", "skip"), calls)
  }

  @Test
  fun tappingThePhotoTargetOpensThePickerUnlessUploading() {
    state = ProfilePhotoUiState(photo = File("picked.jpg"))
    setContent()
    node(ProfilePhotoStepTestTags.TARGET)
        .assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
                .and(hasClickLabel(text(R.string.profile_photo_change))))
        .performClick()
    assertEquals(listOf("add"), calls)
    compose.runOnIdle { state = state.copy(isUploading = true) }
    node(ProfilePhotoStepTestTags.TARGET).assertIsNotEnabled()
  }

  @Test
  fun pickedPhotoOffersSaveAndUploadDisablesAllButSkip() {
    state = ProfilePhotoUiState(photo = File("picked.jpg"))
    setContent()
    node(ProfilePhotoStepTestTags.SAVE).performClick()
    assertEquals(listOf("save"), calls)
    compose.runOnIdle { state = state.copy(isUploading = true) }
    node(ProfilePhotoStepTestTags.PROGRESS).assertExists()
    node(ProfilePhotoStepTestTags.TAKE_PHOTO).assertIsNotEnabled()
    node(ProfilePhotoStepTestTags.FROM_GALLERY).assertIsNotEnabled()
    node(ProfilePhotoStepTestTags.SAVE).assertIsNotEnabled()
    node(ProfilePhotoStepTestTags.SKIP).assertIsEnabled()
  }

  @Test
  fun failedUploadOffersRetryAndSkipWithItsMessage() {
    state = ProfilePhotoUiState(photo = File("picked.jpg"), error = ProfilePhotoError.UPLOAD_FAILED)
    setContent()
    node(ProfilePhotoStepTestTags.ERROR)
        .assertTextEquals(text(R.string.profile_photo_upload_failed))
    node(ProfilePhotoStepTestTags.SAVE).assertDoesNotExist()
    node(ProfilePhotoStepTestTags.RETRY).performClick()
    node(ProfilePhotoStepTestTags.SKIP).assertIsEnabled().performClick()
    assertEquals(listOf("save", "skip"), calls)
  }

  @Test
  fun invalidPhotoShowsItsMessageWithSaveDisabled() {
    state = ProfilePhotoUiState(error = ProfilePhotoError.INVALID_PHOTO)
    setContent()
    node(ProfilePhotoStepTestTags.ERROR).assertTextEquals(text(R.string.personal_photo_invalid))
    node(ProfilePhotoStepTestTags.SAVE).assertIsNotEnabled()
  }

  @Test
  @Config(qualifiers = "w640dp-h320dp")
  fun shortScreenKeepsSkipAndRetryInViewAndScrollsToTheSources() {
    state = ProfilePhotoUiState(photo = File("picked.jpg"), error = ProfilePhotoError.UPLOAD_FAILED)
    setContent()
    node(ProfilePhotoStepTestTags.FROM_GALLERY).assertIsNotDisplayed()
    node(ProfilePhotoStepTestTags.SKIP).assertIsDisplayed()
    node(ProfilePhotoStepTestTags.RETRY).assertIsDisplayed()
    node(ProfilePhotoStepTestTags.FROM_GALLERY).performScrollTo().assertIsDisplayed().performClick()
    node(ProfilePhotoStepTestTags.RETRY).assertIsDisplayed().performClick()
    node(ProfilePhotoStepTestTags.SKIP).assertIsDisplayed().performClick()
    assertEquals(listOf("gallery", "save", "skip"), calls)
  }

  @Test
  fun systemBackSkipsTheStepEvenWhileUploading() {
    val upload = CompletableDeferred<Result<String>>()
    val repository =
        object : ProfilePhotoRepository {
          override suspend fun upload(expectedUid: String, source: File) = upload.await()
        }
    val files =
        object : PersonalPhotoFiles {
          override suspend fun copy(uri: String, fromCamera: Boolean) = File("picked.jpg")

          override suspend fun delete(file: File) = Unit
        }
    val vm = ProfilePhotoViewModel(repository, files, "new-user")
    var done = 0
    compose.setContent { MaterialTheme { ProfilePhotoStepScreen(vm, onDone = { done++ }) } }
    runBlocking { vm.select("content://picked", false) }
    compose.runOnUiThread { vm.save() }
    node(ProfilePhotoStepTestTags.PROGRESS).assertExists()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(1, done) }
  }
}
