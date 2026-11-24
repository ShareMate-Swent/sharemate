// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.sharemate.R

/**
 * A row of segments showing progress through a short flow, such as onboarding. The first
 * [currentStep] segments use the primary color, the others the outline color.
 *
 * @param currentStep The step the user is on, from 1. Values outside `0..stepCount` are clamped.
 * @param stepCount The number of steps in the flow.
 * @param modifier Applied to the indicator.
 */
@Composable
fun AppStepIndicator(currentStep: Int, stepCount: Int, modifier: Modifier = Modifier) {
  val reached = currentStep.coerceIn(0, stepCount)
  val description = stringResource(R.string.step_indicator_description, reached, stepCount)
  Row(
      modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
      horizontalArrangement = Arrangement.spacedBy(ComponentLayout.stepSpacing),
  ) {
    repeat(stepCount) { index ->
      val color =
          if (index < reached) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant
      Box(
          Modifier.weight(1f)
              .height(ComponentLayout.stepHeight)
              .background(color, CircleShape),
      )
    }
  }
}
