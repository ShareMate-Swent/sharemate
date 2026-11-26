// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

/**
 * The round white back button used at the top of a screen.
 *
 * @param onBack Called when the button is tapped.
 * @param testTag Test tag of the button.
 * @param modifier Applied to the button.
 * @param contentDescription Accessible label for the destination-specific back action.
 */
@Composable
fun AppBackButton(
    onBack: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.back),
) {
  Surface(
      onClick = onBack,
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = ComponentLayout.backButtonElevation,
      modifier = modifier.size(ComponentLayout.backButtonSize).testTag(testTag),
  ) {
    Row(
        modifier = Modifier.size(ComponentLayout.backButtonSize),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = contentDescription,
          modifier = Modifier.size(ComponentLayout.backIconSize),
      )
    }
  }
}

/**
 * A back button followed by a title on the same line, for screens that show a list.
 *
 * @param title The screen title.
 * @param onBack Called when the back button is tapped.
 * @param backTestTag Test tag of the back button.
 * @param modifier Applied to the header.
 */
@Composable
fun AppBackHeader(
    title: String,
    onBack: () -> Unit,
    backTestTag: String,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingM),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    AppBackButton(onBack = onBack, testTag = backTestTag)
    SingleLineText(text = title, style = MaterialTheme.typography.headlineSmall)
  }
}

/**
 * A large screen title with an optional description below it.
 *
 * @param title The screen title.
 * @param modifier Applied to the title block.
 * @param description The text under the title, if any.
 */
@Composable
fun AppScreenTitle(title: String, modifier: Modifier = Modifier, description: String? = null) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
    Text(text = title, style = MaterialTheme.typography.headlineLarge)
    description?.let {
      Text(
          text = it,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
