// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.incident

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.sharemate.R
import com.android.sharemate.model.chat.Message
import com.android.sharemate.ui.components.AppBackButton
import com.android.sharemate.ui.theme.Dimens
import java.io.File

/** Stable selectors for the incident report form. */
object IncidentReportTestTags {
  /** Tag for the form container. */
  const val SCREEN = "incident_report_screen"

  /** Tag for the back button closing the form. */
  const val BACK = "incident_report_back"

  /** Tag for the description field. */
  const val DESCRIPTION = "incident_report_description"

  /** Tag for the error shown when the description is too long. */
  const val DESCRIPTION_ERROR = "incident_report_description_error"

  /** Tag for the send button, enabled only when the form is valid. */
  const val SEND = "incident_report_send"
}

/**
 * The "Report an incident" form opened from the chat: a photo and a description, both required. Its
 * ViewModels are scoped to this navigation entry, so the draft survives rotation and its photo is
 * deleted when the form closes.
 *
 * @param onBack Called when the user leaves the form.
 * @param onSend Called with the trimmed description and the attached photo when the user sends a
 *   valid report. The photo stays owned by the form and must be read before it closes.
 */
@Composable
fun IncidentReportRoute(onBack: () -> Unit, onSend: (description: String, photo: File) -> Unit) {
  val photoViewModel = incidentPhotoViewModel(INCIDENT_REPORT_PHOTO_KEY)
  val reportViewModel: IncidentReportViewModel = viewModel()
  val photo by photoViewModel.state.collectAsStateWithLifecycle()
  val description by reportViewModel.description.collectAsStateWithLifecycle()
  IncidentReportForm(
      description = description,
      canSend = IncidentReportRules.canSend(description, photo),
      onDescriptionChange = reportViewModel::onDescriptionChange,
      onSend = { photo.file?.let { onSend(Message.normalizeText(description), it) } },
      onBack = onBack,
  ) {
    IncidentPhotoAttachment(photoViewModel)
  }
}

/**
 * Renders the incident report form from its state.
 *
 * @param description The description as typed.
 * @param canSend Whether the send button is enabled; see [IncidentReportRules.canSend].
 * @param onDescriptionChange Called when the description changes.
 * @param onSend Called when the user presses the enabled send button.
 * @param onBack Called when the user presses the back button.
 * @param modifier Modifier applied to the form.
 * @param photo The photo attachment controls.
 */
@Composable
fun IncidentReportForm(
    description: String,
    canSend: Boolean,
    onDescriptionChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    photo: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val tooLong = IncidentReportRules.isTooLong(description)
  Column(
      modifier
          .fillMaxSize()
          .background(colors.background)
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(Dimens.spacingM)
          .testTag(IncidentReportTestTags.SCREEN),
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
            verticalAlignment = Alignment.CenterVertically) {
              AppBackButton(
                  onBack = onBack,
                  testTag = IncidentReportTestTags.BACK,
                  contentDescription = stringResource(R.string.incident_report_back))
              Text(
                  stringResource(R.string.incident_report_title),
                  color = colors.onBackground,
                  style = MaterialTheme.typography.headlineMedium)
            }
        Text(
            stringResource(R.string.incident_report_hint),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        photo()
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth().testTag(IncidentReportTestTags.DESCRIPTION),
            label = { Text(stringResource(R.string.incident_report_description)) },
            minLines = IncidentReportLayout.descriptionMinLines,
            isError = tooLong,
            supportingText = {
              val length = Message.normalizeText(description).length
              val max = IncidentReportRules.MAX_DESCRIPTION_LENGTH
              if (tooLong) {
                Text(
                    stringResource(R.string.incident_report_description_too_long, length, max),
                    modifier = Modifier.testTag(IncidentReportTestTags.DESCRIPTION_ERROR))
              } else {
                Text(stringResource(R.string.text_length_counter, length, max))
              }
            })
        Button(
            onClick = onSend,
            enabled = canSend,
            modifier =
                Modifier.fillMaxWidth()
                    .height(Dimens.buttonHeight)
                    .testTag(IncidentReportTestTags.SEND)) {
              Text(stringResource(R.string.incident_report_send))
            }
      }
}

/** Sizes used only by the incident report form; shared sizes come from `Dimens`. */
internal object IncidentReportLayout {
  /** Lines the description field shows before it grows. */
  const val descriptionMinLines = 3
}

/** Key of the photo draft owned by the form's navigation entry. */
internal const val INCIDENT_REPORT_PHOTO_KEY = "incident-report-photo"
