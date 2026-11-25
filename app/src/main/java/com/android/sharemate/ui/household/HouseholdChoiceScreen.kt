// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppCard
import com.android.sharemate.ui.components.AppScreenTitle
import com.android.sharemate.ui.components.AppStepIndicator
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [HouseholdChoiceScreen]. */
object HouseholdChoiceTestTags {
  const val CREATE = "household_choice_create"
  const val JOIN = "household_choice_join"
}

/** Lets a user choose whether to create or join a household. */
@Composable
fun HouseholdChoiceScreen(
    onCreateHousehold: () -> Unit,
    onJoinHousehold: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(color = colors.surfaceContainerLow) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
    ) {
      AppStepIndicator(HouseholdLayout.CHOICE_STEP, HouseholdLayout.ONBOARDING_STEP_COUNT)
      Spacer(Modifier.height(Dimens.spacingS))
      AppScreenTitle(
          title = stringResource(R.string.household_choice_title),
          description = stringResource(R.string.household_choice_description),
      )
      ChoiceCard(
          icon = Icons.Outlined.Home,
          iconBackground = colors.primaryContainer,
          iconTint = colors.primary,
          title = stringResource(R.string.create_a_household),
          description = stringResource(R.string.create_a_household_description),
          emphasized = true,
          onClick = onCreateHousehold,
          modifier = Modifier.testTag(HouseholdChoiceTestTags.CREATE),
      )
      ChoiceCard(
          icon = Icons.Outlined.Key,
          iconBackground = colors.secondaryContainer,
          iconTint = colors.onSecondaryContainer,
          title = stringResource(R.string.join_a_household),
          description = stringResource(R.string.join_a_household_description),
          emphasized = false,
          onClick = onJoinHousehold,
          modifier = Modifier.testTag(HouseholdChoiceTestTags.JOIN),
      )
      Spacer(Modifier.weight(1f))
      PrivacyNote()
    }
  }
}

/** One option of the choice screen; the [emphasized] one is outlined in the primary color. */
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    iconBackground: Color,
    iconTint: Color,
    title: String,
    description: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = colors.surface,
      border = if (emphasized) BorderStroke(Dimens.focusedBorderWidth, colors.primary) else null,
  ) {
    Column(
        modifier = Modifier.padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
    ) {
      Box(
          modifier =
              Modifier.size(HouseholdLayout.choiceIconBoxSize)
                  .background(iconBackground, MaterialTheme.shapes.small),
          contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint)
      }
      Text(text = title, style = MaterialTheme.typography.titleLarge)
      Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
      )
    }
  }
}

/** The reassurance at the bottom of the choice screen: a lock and the privacy sentence. */
@Composable
private fun PrivacyNote() {
  AppCard {
    Row(
        modifier = Modifier.padding(vertical = Dimens.spacingS),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = Icons.Outlined.Lock,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(Dimens.iconSmall),
      )
      Text(
          text = stringResource(R.string.household_privacy_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
