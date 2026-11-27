// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.incident

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.ui.incident.IncidentReportRules.MAX_DESCRIPTION_LENGTH
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI tests of the incident report form, alone and with its real photo attachment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncidentReportScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

  private fun showForm(initial: String = "", canSend: Boolean = false): Counters {
    val counters = Counters()
    compose.setContent {
      var description by remember { mutableStateOf(initial) }
      IncidentReportForm(
          description = description,
          canSend = canSend,
          onDescriptionChange = { description = it },
          onSend = { counters.sends++ },
          onBack = { counters.backs++ }) {
            Text("photo slot")
          }
    }
    return counters
  }

  @Test
  fun showsTitlePhotoSlotDescriptionAndCounter() {
    showForm()
    compose.onNodeWithText(string(R.string.incident_report_hint)).assertIsDisplayed()
    compose.onNodeWithText("photo slot").assertIsDisplayed()
    compose.onNodeWithTag(IncidentReportTestTags.DESCRIPTION).assertIsDisplayed()
    compose
        .onNodeWithText(
            string(R.string.text_length_counter, 0, MAX_DESCRIPTION_LENGTH), useUnmergedTree = true)
        .assertExists()
  }

  @Test
  fun sendButtonFollowsCanSend() {
    showForm(canSend = false)
    compose.onNodeWithTag(IncidentReportTestTags.SEND).assertIsNotEnabled()
  }

  @Test
  fun enabledSendAndBackCallTheirActions() {
    val counters = showForm(initial = "Leak", canSend = true)
    compose.onNodeWithTag(IncidentReportTestTags.SEND).performScrollTo().assertIsEnabled()
    compose.onNodeWithTag(IncidentReportTestTags.SEND).performClick()
    compose.onNodeWithTag(IncidentReportTestTags.BACK).performClick()
    assertEquals(1, counters.sends)
    assertEquals(1, counters.backs)
  }

  @Test
  fun counterCountsTrimmedCharacters() {
    showForm()
    compose.onNodeWithTag(IncidentReportTestTags.DESCRIPTION).performTextInput("  Leak  ")
    compose
        .onNodeWithText(
            string(R.string.text_length_counter, 4, MAX_DESCRIPTION_LENGTH), useUnmergedTree = true)
        .assertExists()
    compose
        .onAllNodesWithTag(IncidentReportTestTags.DESCRIPTION_ERROR, useUnmergedTree = true)
        .assertCountEquals(0)
  }

  @Test
  fun tooLongDescriptionShowsAnError() {
    showForm(initial = "a".repeat(MAX_DESCRIPTION_LENGTH + 1))
    compose
        .onNodeWithTag(IncidentReportTestTags.DESCRIPTION_ERROR, useUnmergedTree = true)
        .assertExists()
  }

  @Test
  fun routeCannotSendWithoutAPhoto() {
    compose.setContent { IncidentReportRoute(onBack = {}, onSend = { _, _ -> }) }
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).assertIsDisplayed()
    compose.onNodeWithTag(IncidentReportTestTags.DESCRIPTION).performTextInput("Leak")
    compose.onNodeWithTag(IncidentReportTestTags.SEND).assertIsNotEnabled()
  }

  @Test
  fun routeSendsTheTrimmedDescriptionAndThePhoto() {
    val photo = File(compose.activity.cacheDir, "incident.jpg").apply { writeText("jpeg") }
    val photoViewModel = attachedPhoto(photo)
    var sent: Pair<String, File>? = null
    compose.setContent { IncidentReportRoute(onBack = {}, onSend = { d, f -> sent = d to f }) }
    val send = compose.onNodeWithTag(IncidentReportTestTags.SEND)
    send.performScrollTo().assertIsNotEnabled()
    compose
        .onNodeWithTag(IncidentReportTestTags.DESCRIPTION)
        .performTextReplacement("  Leak under the sink ")
    send.performScrollTo().assertIsEnabled().performClick()
    assertEquals("Leak under the sink", sent?.first)
    assertSame(photo, sent?.second)
    assertSame(photo, photoViewModel.state.value.file)
  }

  @Test
  fun routeBackLeavesWithoutSending() {
    var backs = 0
    var sent: String? = null
    compose.setContent { IncidentReportRoute(onBack = { backs++ }, onSend = { d, _ -> sent = d }) }
    compose.onNodeWithTag(IncidentReportTestTags.BACK).performClick()
    assertEquals(1, backs)
    assertNull(sent)
  }

  /** Registers the form's photo draft under its key with [photo] already confirmed. */
  private fun attachedPhoto(photo: File): IncidentPhotoViewModel {
    val files =
        object : PersonalPhotoFiles {
          override suspend fun copy(uri: String, fromCamera: Boolean) = photo

          override suspend fun delete(file: File) = Unit
        }
    val viewModel =
        ViewModelProvider(
            compose.activity, viewModelFactory { initializer { IncidentPhotoViewModel(files) } })[
            INCIDENT_REPORT_PHOTO_KEY, IncidentPhotoViewModel::class.java]
    runBlocking { viewModel.selectAndWait("content://photo", fromCamera = false) }
    return viewModel
  }

  private class Counters {
    var sends = 0
    var backs = 0
  }
}
