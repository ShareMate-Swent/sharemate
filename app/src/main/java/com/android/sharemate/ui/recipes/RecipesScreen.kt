// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.theme.Dimens

@Composable
fun RecipesScreen() {
  Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
    Text(
        text = stringResource(R.string.recipes_title),
        modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(text = stringResource(R.string.recipes_coming_soon))
  }
}
