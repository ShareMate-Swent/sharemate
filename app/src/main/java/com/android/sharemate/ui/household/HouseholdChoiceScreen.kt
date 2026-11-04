// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.android.sharemate.R
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
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(Dimens.spacingL),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(HouseholdLayout.headerTopSpacing))
    Box(
        modifier =
            Modifier.size(HouseholdLayout.logoSize)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          imageVector = Icons.Outlined.Kitchen,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(HouseholdLayout.logoIconSize),
      )
    }
    Spacer(Modifier.height(Dimens.spacingM))
    Text(
        text = stringResource(R.string.household_choice_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Dimens.spacingXs))
    Text(
        text = stringResource(R.string.household_choice_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Dimens.spacingXl),
    )
    Spacer(Modifier.weight(1f))
    ChoiceCard(
        icon = Icons.Outlined.Home,
        title = stringResource(R.string.create_a_household),
        description = stringResource(R.string.create_a_household_description),
        emphasized = true,
        onClick = onCreateHousehold,
        modifier = Modifier.testTag(HouseholdChoiceTestTags.CREATE),
    )
    Spacer(Modifier.height(Dimens.spacingM))
    ChoiceCard(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.join_a_household),
        description = stringResource(R.string.join_a_household_description),
        emphasized = false,
        onClick = onJoinHousehold,
        modifier = Modifier.testTag(HouseholdChoiceTestTags.JOIN),
    )
    Spacer(Modifier.weight(1f))
    Text(
        text = stringResource(R.string.household_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
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
      shape = RoundedCornerShape(HouseholdLayout.cardCornerRadius),
      color = colors.surfaceContainerLowest,
      border = BorderStroke(Dimens.borderWidth, colors.primaryContainer),
      shadowElevation = Dimens.cardElevation,
  ) {
    Row(
        modifier =
            Modifier.heightIn(min = HouseholdLayout.choiceCardMinHeight)
                .padding(Dimens.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
          modifier =
              Modifier.size(HouseholdLayout.choiceIconBoxSize)
                  .background(
                      if (emphasized) colors.primaryContainer else colors.surfaceVariant,
                      RoundedCornerShape(HouseholdLayout.choiceIconBoxCornerRadius)),
          contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.primary)
      }
      Spacer(Modifier.width(Dimens.spacingM))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Dimens.spacingXxs))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(Dimens.spacingS))
      Box(
          modifier =
              Modifier.size(HouseholdLayout.choiceArrowBoxSize)
                  .background(
                      if (emphasized) colors.primary else colors.surfaceVariant, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (emphasized) colors.onPrimary else colors.primary,
            modifier = Modifier.size(HouseholdLayout.choiceArrowIconSize),
        )
      }
    }
  }
}
