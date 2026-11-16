// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * callbacks, and that system back skips the step, also while uploading.
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
              onSave = { calls += "save" },
              onSkip = { calls += "skip" })
        }
      }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun text(id: Int) = compose.activity.getString(id)

  @Test
  fun initialStepOffersAddAndSkipButNoSave() {
    setContent()
    node(ProfilePhotoStepTestTags.ADD_PHOTO).assertTextEquals(text(R.string.profile_photo_add))
    node(ProfilePhotoStepTestTags.PLACEHOLDER).assertExists()
    for (tag in
        listOf(
            ProfilePhotoStepTestTags.SAVE,
            ProfilePhotoStepTestTags.RETRY,
            ProfilePhotoStepTestTags.PROGRESS,
            ProfilePhotoStepTestTags.ERROR)) {
      node(tag).assertDoesNotExist()
    }
    node(ProfilePhotoStepTestTags.ADD_PHOTO).performClick()
    node(ProfilePhotoStepTestTags.SKIP).performClick()
    assertEquals(listOf("add", "skip"), calls)
  }

  @Test
  fun pickedPhotoOffersSaveAndUploadDisablesAllButSkip() {
    state = ProfilePhotoUiState(photo = File("picked.jpg"))
    setContent()
    node(ProfilePhotoStepTestTags.ADD_PHOTO).assertTextEquals(text(R.string.profile_photo_change))
    node(ProfilePhotoStepTestTags.SAVE).performClick()
    assertEquals(listOf("save"), calls)
    compose.runOnIdle { state = state.copy(isUploading = true) }
    node(ProfilePhotoStepTestTags.PROGRESS).assertExists()
    node(ProfilePhotoStepTestTags.ADD_PHOTO).assertIsNotEnabled()
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
  fun invalidPhotoShowsItsMessageWithoutSave() {
    state = ProfilePhotoUiState(error = ProfilePhotoError.INVALID_PHOTO)
    setContent()
    node(ProfilePhotoStepTestTags.ERROR).assertTextEquals(text(R.string.personal_photo_invalid))
    node(ProfilePhotoStepTestTags.SAVE).assertDoesNotExist()
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
