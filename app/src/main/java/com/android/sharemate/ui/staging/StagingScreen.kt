// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.staging.Prices
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.household.HouseholdTopBar

object StagingTestTags {
  const val BACK = "staging_back"
  const val LIST = "staging_list"
  const val EMPTY = "staging_empty"
  const val CONFIRM = "staging_confirm"

  fun item(id: String) = "staging_item_$id"
}

/**
 * Staging screen where the user reviews scanned items before saving them. [onConfirm] receives the
 * final list; saving it is up to the caller (US-25).
 */
@Composable
fun StagingScreen(
    viewModel: StagingViewModel,
    onConfirm: (List<StagedItem>) -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  StagingContent(
      state = state,
      onConfirm = { viewModel.confirmedItems()?.let(onConfirm) },
      onBack = onBack,
  )
}

/** Stateless content of [StagingScreen], so it can be tested and previewed with any state. */
@Composable
fun StagingContent(state: StagingUiState, onConfirm: () -> Unit, onBack: () -> Unit) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    HouseholdTopBar(
        title = stringResource(R.string.staging_title),
        onBack = onBack,
        backTestTag = StagingTestTags.BACK,
    )
    Text(
        text = stringResource(R.string.staging_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (state.items.isEmpty()) {
        Text(
            text = stringResource(R.string.staging_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).testTag(StagingTestTags.EMPTY),
        )
      } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(StagingTestTags.LIST),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(state.items, key = { it.id }) { item -> StagedItemRow(item) }
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onConfirm,
        enabled = state.canConfirm,
        modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.CONFIRM),
    ) {
      Text(stringResource(R.string.staging_confirm))
    }
  }
}

@Composable
private fun StagedItemRow(item: StagedItem) {
  Card(
      modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.item(item.id)),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.staging_quantity, item.quantity),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(12.dp))
      Text(
          text =
              item.priceCents?.let { stringResource(R.string.staging_price, Prices.format(it)) }
                  ?: stringResource(R.string.staging_no_price),
          style = MaterialTheme.typography.bodyLarge,
          color =
              if (item.priceCents == null) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
