// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.incident

import androidx.lifecycle.ViewModel
import com.android.sharemate.model.chat.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rules of the incident report form. Both a photo and a description are required, because the chat
 * rules and `ChatRepository.sendIncident` refuse an incident without either.
 */
object IncidentReportRules {
  /** Most characters of a description once trimmed; the same limit as a chat message. */
  const val MAX_DESCRIPTION_LENGTH = Message.MAX_TEXT_LENGTH

  /** Whether [description] is not blank and at most [MAX_DESCRIPTION_LENGTH] once trimmed. */
  fun isValidDescription(description: String): Boolean = Message.isValidText(description)

  /** Whether [description] is longer than [MAX_DESCRIPTION_LENGTH] once trimmed. */
  fun isTooLong(description: String): Boolean =
      Message.normalizeText(description).length > MAX_DESCRIPTION_LENGTH

  /**
   * Whether the report can be sent: [photo] holds a confirmed attachment that is not being replaced
   * and [description] is valid.
   */
  fun canSend(description: String, photo: IncidentPhotoState): Boolean =
      photo.file != null && !photo.importing && isValidDescription(description)
}

/**
 * Holds the description of one incident report draft. Scope it to the form's navigation entry, like
 * [IncidentPhotoViewModel], so the text survives rotation and is dropped when the form closes.
 */
class IncidentReportViewModel : ViewModel() {
  private val mutableDescription = MutableStateFlow("")

  /** The description as typed, before trimming. */
  val description = mutableDescription.asStateFlow()

  /** Replaces the description with [text]. */
  fun onDescriptionChange(text: String) {
    mutableDescription.value = text
  }
}
