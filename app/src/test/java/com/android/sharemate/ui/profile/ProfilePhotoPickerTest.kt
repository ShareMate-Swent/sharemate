// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Checks that [ProfilePhotoPicker] hands a confirmed gallery photo over as a gallery photo, then
 * resets the dialog, and discards the dialog's image when it leaves composition.
 */
@RunWith(RobolectricTestRunner::class)
class ProfilePhotoPickerTest {
  @get:Rule val compose = createComposeRule()
  private val uri =
      Uri.fromFile(
              File.createTempFile("profile", ".png", RuntimeEnvironment.getApplication().cacheDir))
          .toString()
  private val store =
      object : TemporaryPhotoStore {
        override suspend fun createCapture() = uri

        override suspend fun importImage(uri: String) = this@ProfilePhotoPickerTest.uri

        override suspend fun validate(uri: String) = Unit

        override suspend fun delete(uri: String) = Unit
      }
  private val camera = PhotoPickerViewModel(store, SavedStateHandle())

  @Test
  fun confirmedGalleryPhotoIsHandedOverThenTheDialogResets() {
    val confirmed = mutableListOf<Pair<String, Boolean>>()
    compose.setContent {
      MaterialTheme {
        ProfilePhotoPicker({ uri, fromCamera -> confirmed += uri to fromCamera }, camera)
      }
    }
    compose.runOnIdle {
      camera.open()
      camera.select("content://photos/picked")
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(listOf(uri to false), confirmed)
      assertNull(camera.state.value.uri)
      assertFalse(camera.state.value.confirmed)
    }
  }

  @Test
  fun leavingCompositionDiscardsTheDialogsImage() {
    var shown by mutableStateOf(true)
    compose.setContent { if (shown) ProfilePhotoPicker({ _, _ -> }, camera) }
    compose.runOnIdle {
      camera.open()
      camera.select("content://photos/picked")
    }
    compose.runOnIdle { shown = false }
    compose.runOnIdle {
      assertFalse(camera.state.value.visible)
      assertNull(camera.state.value.uri)
    }
  }
}
