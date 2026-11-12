// Co-authored-by: OpenAI Codex <noreply@openai.com>
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.android.sharemate.R
import com.android.sharemate.model.staging.Prices
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.household.HouseholdTopBar
import com.android.sharemate.ui.theme.Dimens

private const val ITEM_NAME_MAX_LINES = 1

object StagingTestTags {
  const val BACK = "staging_back"
  const val LIST = "staging_list"
  const val EMPTY = "staging_empty"
  const val CONFIRM = "staging_confirm"
  const val ADD = "staging_add"

  fun item(id: String) = "staging_item_$id"

  fun delete(id: String) = "staging_delete_$id"
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
    actionsEnabled: Boolean = true,
    statusContent: @Composable () -> Unit = {},
) {
  val state = viewModel.state.collectAsState().value
  StagingContent(
      state = state,
      onItemClick = { if (actionsEnabled) viewModel.startEditing(it) },
      onDeleteItem = { if (actionsEnabled) viewModel.deleteItem(it) },
      onConfirm = { if (actionsEnabled) viewModel.confirmedItems()?.let(onConfirm) },
      onBack = onBack,
      actionsEnabled = actionsEnabled,
      statusContent = statusContent,
      onAddItem = viewModel::startAdding,
  )
  state.draft
      ?.takeIf { actionsEnabled }
      ?.let { draft ->
        StagedItemEditDialog(
            draft = draft,
            onNameChange = viewModel::updateDraftName,
            onQuantityChange = viewModel::updateDraftQuantity,
            onPriceChange = viewModel::updateDraftPrice,
            onSave = viewModel::saveDraft,
            onCancel = viewModel::cancelEditing,
        )
      }
}

/** Stateless content of [StagingScreen], so it can be tested and previewed with any state. */
@Composable
fun StagingContent(
    state: StagingUiState,
    onItemClick: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    actionsEnabled: Boolean = true,
    statusContent: @Composable () -> Unit = {},
    onAddItem: (() -> Unit)? = null,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(
                  horizontal = Dimens.screenHorizontalPadding,
                  vertical = Dimens.screenVerticalPadding),
  ) {
    HouseholdTopBar(
        title = stringResource(R.string.staging_title),
        onBack = onBack,
        backTestTag = StagingTestTags.BACK,
        actions = {
          if (onAddItem != null) {
            TextButton(
                onClick = onAddItem,
                enabled = actionsEnabled,
                modifier = Modifier.testTag(StagingTestTags.ADD),
            ) {
              Text(stringResource(R.string.staging_add_item))
            }
          }
        },
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
          modifier =
              Modifier.fillMaxSize()
                  .then(
                      if (state.items.isEmpty()) Modifier
                      else Modifier.testTag(StagingTestTags.LIST)),
          verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
      ) {
        item(key = DESCRIPTION_KEY) {
          Text(
              text = stringResource(R.string.staging_description),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(Dimens.spacingM))
        }
        if (state.items.isEmpty()) {
          item(key = EMPTY_KEY) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = Dimens.spacingM),
                contentAlignment = Alignment.Center) {
                  Text(
                      text = stringResource(R.string.staging_empty),
                      style = MaterialTheme.typography.bodyLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.testTag(StagingTestTags.EMPTY),
                  )
                }
          }
        } else {
          items(state.items, key = { it.id }) { item ->
            StagedItemRow(
                item,
                onClick = { onItemClick(item.id) },
                onDelete = { onDeleteItem(item.id) },
                enabled = actionsEnabled,
            )
          }
        }
        item(key = STATUS_KEY) { statusContent() }
      }
    }
    Spacer(Modifier.height(Dimens.spacingM))
    Button(
        onClick = onConfirm,
        enabled = state.canConfirm && actionsEnabled,
        modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.CONFIRM),
    ) {
      Text(stringResource(R.string.staging_confirm))
    }
  }
}

// Section keys are integers, distinct from the String identifiers of reviewed rows.
private const val DESCRIPTION_KEY = 0
private const val EMPTY_KEY = 1
private const val STATUS_KEY = 2

/** A staged item: tap to edit it, swipe it to the left or tap its delete button to remove it. */
@OptIn(ExperimentalMaterial3Api::class) // SwipeToDismissBox is still experimental in Material 3.
@Composable
private fun StagedItemRow(
    item: StagedItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
  val currentOnDelete by rememberUpdatedState(onDelete)
  val currentEnabled by rememberUpdatedState(enabled)
  val dismissState =
      rememberSwipeToDismissBoxState(
          confirmValueChange = { value ->
            val dismissed = currentEnabled && value == SwipeToDismissBoxValue.EndToStart
            if (dismissed) currentOnDelete()
            dismissed
          })
  SwipeToDismissBox(
      state = dismissState,
      enableDismissFromStartToEnd = false,
      enableDismissFromEndToStart = enabled,
      backgroundContent = { DeleteBackground() },
  ) {
    StagedItemCard(item, onClick, onDelete, enabled)
  }
}

/** Shown behind a row while it is swiped away. */
@Composable
private fun DeleteBackground() {
  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.errorContainer, CardDefaults.shape)
              .padding(horizontal = Dimens.cardPadding),
      contentAlignment = Alignment.CenterEnd,
  ) {
    Icon(
        imageVector = Icons.Outlined.Delete,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

@Composable
private fun StagedItemCard(
    item: StagedItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
  Card(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.item(item.id)),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Dimens.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = ITEM_NAME_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.staging_quantity, item.quantity),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(Dimens.spacingS))
      Text(
          text =
              item.priceCents?.let { stringResource(R.string.staging_price, Prices.format(it)) }
                  ?: stringResource(R.string.staging_no_price),
          style = MaterialTheme.typography.bodyLarge,
          color =
              if (item.priceCents == null) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
      )
      IconButton(
          onClick = onDelete,
          enabled = enabled,
          modifier = Modifier.testTag(StagingTestTags.delete(item.id))) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.staging_delete_item, item.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
    }
  }
}
