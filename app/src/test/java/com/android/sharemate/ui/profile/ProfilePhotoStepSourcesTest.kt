// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.profile

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.model.profile.ProfilePhotoRepository
import com.android.sharemate.ui.photo.PhotoPage
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Checks that the camera and gallery cards of the profile photo step reach the right source: the
 * camera directly when allowed, after the permission prompt otherwise, the gallery straight into
 * the preview, and the camera/gallery dialog whenever a source cannot be opened.
 */
@RunWith(RobolectricTestRunner::class)
class ProfilePhotoStepSourcesTest {
  @get:Rule val compose = createComposeRule()

  private val files = RecordingFiles()
  private val viewModel = ProfilePhotoViewModel(NoUpload, files, "new-user")
  private val camera = PhotoPickerViewModel(NoPhotoStore, SavedStateHandle())

  /** What each external activity answers, and how often each was launched. */
  private var permissionGranted: Boolean? = null
  private var galleryResult: Uri? = null
  private var deferGalleryResult = false
  private var completeGallery: () -> Unit = {}
  private var unavailable = false
  private val launched = mutableListOf<String>()

  /**
   * Every page the open dialog showed. The camera cannot start under Robolectric, so the dialog
   * goes back to its sources right after showing the camera page.
   */
  private val pages = mutableListOf<PhotoPage>()

  private val registry =
      object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
          if (unavailable) throw ActivityNotFoundException("No handler")
          when (contract) {
            is ActivityResultContracts.RequestPermission -> {
              assertEquals(Manifest.permission.CAMERA, input)
              launched += "permission"
              dispatchResult(requestCode, permissionGranted)
            }
            is ActivityResultContracts.PickVisualMedia -> {
              launched += "gallery"
              completeGallery = { dispatchResult(requestCode, galleryResult) }
              if (!deferGalleryResult) completeGallery()
            }
            else -> error("Unexpected external activity")
          }
        }
      }

  private fun setScreen(cameraAllowed: Boolean) {
    val app = shadowOf(RuntimeEnvironment.getApplication())
    if (cameraAllowed) app.grantPermissions(Manifest.permission.CAMERA)
    else app.denyPermissions(Manifest.permission.CAMERA)
    val owner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry = registry
        }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        LaunchedEffect(Unit) {
          camera.state.collect { if (it.visible && pages.lastOrNull() != it.page) pages += it.page }
        }
        MaterialTheme { ProfilePhotoStepScreen(viewModel, onDone = {}, camera = camera) }
      }
    }
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

  /** Asserts the dialog was opened, and whether it went to the camera page. */
  private fun assertDialogOpened(onCamera: Boolean) =
      compose.runOnIdle {
        assertTrue(pages.isNotEmpty())
        assertEquals(onCamera, PhotoPage.CAMERA in pages)
      }

  @Test
  fun allowedCameraOpensStraightOnTheCameraPage() {
    setScreen(cameraAllowed = true)
    click(ProfilePhotoStepTestTags.TAKE_PHOTO)
    assertDialogOpened(onCamera = true)
    compose.runOnIdle { assertEquals(emptyList<String>(), launched) }
  }

  @Test
  fun cameraPermissionIsAskedFirstThenTheCameraOpens() {
    permissionGranted = true
    setScreen(cameraAllowed = false)
    click(ProfilePhotoStepTestTags.TAKE_PHOTO)
    assertDialogOpened(onCamera = true)
    compose.runOnIdle { assertEquals(listOf("permission"), launched) }
  }

  @Test
  fun refusedCameraPermissionOpensTheDialogOnItsSources() {
    permissionGranted = false
    setScreen(cameraAllowed = false)
    click(ProfilePhotoStepTestTags.TAKE_PHOTO)
    assertDialogOpened(onCamera = false)
  }

  @Test
  fun galleryPickIsCopiedAndPreviewedWithoutTheDialog() {
    galleryResult = Uri.parse("content://media/picked")
    setScreen(cameraAllowed = true)
    click(ProfilePhotoStepTestTags.FROM_GALLERY)
    compose.waitUntil { viewModel.state.value.photo != null }
    compose.runOnIdle {
      assertEquals(listOf("content://media/picked" to false), files.copies)
      assertFalse(camera.state.value.visible)
    }
  }

  @Test
  fun doubleTapOpensOneGalleryAndCancellationAllowsAnother() {
    deferGalleryResult = true
    setScreen(cameraAllowed = true)
    click(ProfilePhotoStepTestTags.FROM_GALLERY)
    click(ProfilePhotoStepTestTags.FROM_GALLERY)
    compose.runOnIdle {
      assertEquals(listOf("gallery"), launched)
      completeGallery()
      assertTrue(files.copies.isEmpty())
      assertEquals(null, viewModel.state.value.photo)
    }
    galleryResult = Uri.parse("content://media/picked")
    click(ProfilePhotoStepTestTags.FROM_GALLERY)
    compose.runOnIdle {
      assertEquals(listOf("gallery", "gallery"), launched)
      completeGallery()
    }
    compose.waitUntil { viewModel.state.value.photo != null }
  }

  @Test
  fun unavailableSourcesFallBackToTheDialog() {
    unavailable = true
    setScreen(cameraAllowed = false)
    click(ProfilePhotoStepTestTags.TAKE_PHOTO)
    assertDialogOpened(onCamera = false)
    compose.runOnIdle {
      camera.closePreview()
      pages.clear()
    }
    click(ProfilePhotoStepTestTags.FROM_GALLERY)
    assertDialogOpened(onCamera = false)
  }

  private object NoUpload : ProfilePhotoRepository {
    override suspend fun upload(expectedUid: String, source: File) = Result.success("url")
  }

  private object NoPhotoStore : TemporaryPhotoStore {
    override suspend fun createCapture() = error("No capture in these tests")

    override suspend fun importImage(uri: String) = uri

    override suspend fun validate(uri: String) = Unit

    override suspend fun delete(uri: String) = Unit
  }

  private class RecordingFiles : PersonalPhotoFiles {
    val copies = mutableListOf<Pair<String, Boolean>>()

    override suspend fun copy(uri: String, fromCamera: Boolean): File {
      copies += uri to fromCamera
      return File("copy.jpg")
    }

    override suspend fun delete(file: File) = Unit
  }
}
