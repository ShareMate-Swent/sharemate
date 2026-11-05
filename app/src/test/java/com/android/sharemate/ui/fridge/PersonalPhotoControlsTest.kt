// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.image.PersonalPhotoDraftError
import com.android.sharemate.model.image.PersonalPhotoDraftException
import com.android.sharemate.model.image.PersonalPhotoDraftStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class PersonalPhotoControlsTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val drafts = Drafts()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val controller = PersonalPhotoDraftController(drafts, scope)

  @Before
  fun setUp() {
    drafts.next = temporary.newFile("selected.png")
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    drafts.next.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
  }

  @After
  fun tearDown() {
    controller.close()
    scope.cancel()
  }

  @Test
  fun selectedPhotoShowsPreviewAndRemoveClearsOnlyTheDraft() {
    showControls()
    compose.runOnIdle { controller.select("content://photos/selected") }
    compose.onNodeWithTag("personal_photo_preview").assertIsDisplayed()
    compose.onNodeWithText("Change personal photo").assertIsDisplayed()
    compose.runOnIdle { controller.select(null) }
    compose.onNodeWithTag("personal_photo_preview").assertIsDisplayed()

    compose.onNodeWithTag("personal_photo_remove").performClick()
    compose.onNodeWithTag("personal_photo_preview").assertDoesNotExist()
    compose.onNodeWithText("Choose a personal photo").assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(drafts.next), drafts.deleted) }
  }

  @Test
  fun rejectedReplacementShowsAnActionableErrorAndKeepsThePreviousPreview() {
    showControls()
    compose.runOnIdle { controller.select("content://photos/selected") }
    compose.onNodeWithTag("personal_photo_preview").assertIsDisplayed()
    for ((reason, message) in
        listOf(
            PersonalPhotoDraftError.UNSUPPORTED to "Choose a JPEG or PNG photo.",
            PersonalPhotoDraftError.TOO_LARGE to "Choose a photo smaller than 5 MiB.",
            PersonalPhotoDraftError.INVALID to "This image cannot be read. Choose another photo.",
            PersonalPhotoDraftError.UNREADABLE to "This photo is unavailable. Choose it again.")) {
      compose.runOnIdle {
        drafts.failure = PersonalPhotoDraftException(reason)
        controller.select("content://photos/rejected")
      }
      compose
          .onNodeWithTag("personal_photo_selection_error")
          .assertIsDisplayed()
          .assertTextEquals(message)
      compose.onNodeWithTag("personal_photo_preview").assertIsDisplayed()
      compose.runOnIdle { assertTrue(drafts.deleted.isEmpty()) }
    }
  }

  private fun showControls() {
    compose.setContent { MaterialTheme { PersonalPhotoPicker(controller, enabled = true) } }
  }

  private class Drafts : PersonalPhotoDraftStore {
    lateinit var next: File
    var failure: PersonalPhotoDraftException? = null
    val deleted = mutableListOf<File>()

    override suspend fun importPhoto(uri: String): File {
      failure?.let { throw it }
      return next
    }

    override suspend fun delete(file: File) {
      deleted += file
      file.delete()
    }
  }
}
