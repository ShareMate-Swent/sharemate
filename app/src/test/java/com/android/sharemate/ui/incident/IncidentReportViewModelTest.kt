// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.incident

import com.android.sharemate.ui.incident.IncidentReportRules.MAX_DESCRIPTION_LENGTH
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation of the incident report form, including the description length boundaries. */
class IncidentReportViewModelTest {
  private val photo = IncidentPhotoState(file = File("incident.jpg"))

  @Test
  fun emptyOrBlankDescriptionIsInvalid() {
    assertFalse(IncidentReportRules.isValidDescription(""))
    assertFalse(IncidentReportRules.isValidDescription("   \n\t "))
  }

  @Test
  fun oneCharacterDescriptionIsValid() {
    assertTrue(IncidentReportRules.isValidDescription("a"))
  }

  @Test
  fun descriptionAtTheLimitIsValidAndOneMoreIsNot() {
    val atLimit = "a".repeat(MAX_DESCRIPTION_LENGTH)
    assertTrue(IncidentReportRules.isValidDescription(atLimit))
    assertFalse(IncidentReportRules.isTooLong(atLimit))
    val overLimit = "a".repeat(MAX_DESCRIPTION_LENGTH + 1)
    assertFalse(IncidentReportRules.isValidDescription(overLimit))
    assertTrue(IncidentReportRules.isTooLong(overLimit))
  }

  @Test
  fun surroundingSpacesDoNotCountTowardsTheLimit() {
    val padded = "  " + "a".repeat(MAX_DESCRIPTION_LENGTH) + "  "
    assertTrue(IncidentReportRules.isValidDescription(padded))
    assertFalse(IncidentReportRules.isTooLong(padded))
  }

  @Test
  fun canSendOnlyWithAPhotoAndAValidDescription() {
    assertTrue(IncidentReportRules.canSend("Leak under the sink", photo))
    assertFalse(IncidentReportRules.canSend("Leak under the sink", IncidentPhotoState()))
    assertFalse(IncidentReportRules.canSend(" ", photo))
    assertFalse(IncidentReportRules.canSend("a".repeat(MAX_DESCRIPTION_LENGTH + 1), photo))
  }

  @Test
  fun cannotSendWhileThePhotoIsBeingReplaced() {
    assertFalse(IncidentReportRules.canSend("Leak", photo.copy(importing = true)))
  }

  @Test
  fun viewModelKeepsTheDescriptionAsTyped() {
    val viewModel = IncidentReportViewModel()
    assertEquals("", viewModel.description.value)
    viewModel.onDescriptionChange("  Broken window ")
    assertEquals("  Broken window ", viewModel.description.value)
  }
}
