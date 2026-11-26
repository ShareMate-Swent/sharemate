// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.android.sharemate.ui.receipts.ReceiptHeader
import com.android.sharemate.ui.receipts.ReceiptProgress
import com.android.sharemate.ui.theme.Dimens

private const val ITEM_NAME_MAX_LINES = 1

object StagingTestTags {
  const val BACK = "staging_back"
  const val LIST = "staging_list"
  const val EMPTY = "staging_empty"
  const val CONFIRM = "staging_confirm"
  const val ADD = "staging_add"
  const val TOTAL = "staging_total"

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
    onConfirmPurchase: ((ReviewedPurchase) -> Unit)? = null,
    separateSharingStep: Boolean = false,
    previewContent: @Composable () -> Unit = {},
    savedPurchase: Boolean = false,
    savedPreview: PurchasePreview? = null,
    onDeleteReceipt: (() -> Unit)? = null,
) {
  val state = viewModel.state.collectAsState().value
  var sharing by rememberSaveable { mutableStateOf(false) }
  val stagedPurchase = separateSharingStep && state.purchase != null
  BackHandler(enabled = stagedPurchase && sharing) { sharing = false }
  if (stagedPurchase && sharing) {
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenHorizontalPadding)) {
          ReceiptHeader(
              title = stringResource(R.string.receipt_share_expense),
              onBack = { sharing = false },
              backTag = StagingTestTags.BACK,
              actions = { ReceiptDeleteActions(onDeleteReceipt) })
          ReceiptProgress(if (savedPurchase) 3 else 2)
          PurchaseReviewContent(
              savedPreview ?: state.purchasePreview,
              checkNotNull(state.purchase),
              viewModel::selectPayer,
              viewModel::toggleParticipant,
              actionsEnabled)
          if (!savedPurchase)
              Button(
                  shape = RoundedCornerShape(14.dp),
                  onClick = {
                    viewModel.confirmedPurchase()?.let { onConfirmPurchase?.invoke(it) }
                  },
                  enabled =
                      actionsEnabled &&
                          state.canConfirm &&
                          state.purchasePreview?.error == null &&
                          onConfirmPurchase != null,
                  modifier = Modifier.fillMaxWidth().testTag("staging_confirm_purchase")) {
                    Text(stringResource(R.string.receipt_save_purchase))
                  }
          statusContent()
        }
  } else {
    StagingContent(
        state = state,
        onItemClick = { if (actionsEnabled) viewModel.startEditing(it) },
        onDeleteItem = { if (actionsEnabled) viewModel.deleteItem(it) },
        onConfirm = {
          if (actionsEnabled || savedPurchase) {
            if (stagedPurchase) sharing = true else viewModel.confirmedItems()?.let(onConfirm)
          }
        },
        onBack = onBack,
        actionsEnabled = actionsEnabled,
        statusContent = {
          state.purchase
              ?.takeUnless { stagedPurchase }
              ?.let {
                PurchaseReviewContent(
                    state.purchasePreview,
                    it,
                    viewModel::selectPayer,
                    viewModel::toggleParticipant,
                    actionsEnabled,
                )
                Button(
                    onClick = {
                      viewModel.confirmedPurchase()?.let { onConfirmPurchase?.invoke(it) }
                    },
                    enabled =
                        actionsEnabled &&
                            state.canConfirm &&
                            state.purchasePreview?.error == null &&
                            onConfirmPurchase != null,
                    modifier = Modifier.fillMaxWidth().testTag("staging_confirm_purchase"),
                ) {
                  Text(stringResource(R.string.purchase_confirm))
                }
              }
          statusContent()
        },
        onAddItem = viewModel::startAdding,
        continueToSharing = stagedPurchase,
        previewContent = previewContent,
        savedPurchase = savedPurchase,
        onDeleteReceipt = onDeleteReceipt,
    )
  }
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
    continueToSharing: Boolean = false,
    previewContent: @Composable () -> Unit = {},
    savedPurchase: Boolean = false,
    onDeleteReceipt: (() -> Unit)? = null,
) {
  var showMissingPrices by remember { mutableStateOf(false) }
  if (!state.hasMissingPrices) showMissingPrices = false
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(
                  horizontal = Dimens.screenHorizontalPadding,
                  vertical = Dimens.screenVerticalPadding,
              )) {
        ReceiptHeader(
            title = stringResource(R.string.staging_title),
            onBack = onBack,
            backTag = StagingTestTags.BACK,
            actions = { ReceiptDeleteActions(onDeleteReceipt) },
        )
        if (continueToSharing) {
          ReceiptProgress(if (savedPurchase) 3 else 1)
          previewContent()
          Spacer(Modifier.height(Dimens.spacingS))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
          LazyColumn(
              modifier =
                  Modifier.fillMaxSize()
                      .then(
                          if (state.items.isEmpty()) Modifier
                          else Modifier.testTag(StagingTestTags.LIST)),
              verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
          ) {
            if (!continueToSharing)
                item(key = DESCRIPTION_KEY) {
                  previewContent()
                  if (!continueToSharing)
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
                    contentAlignment = Alignment.Center,
                ) {
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
                    compact = continueToSharing,
                )
              }
            }
            if (!continueToSharing)
                item(key = STATUS_KEY) {
                  if (onAddItem != null && !savedPurchase) {
                    androidx.compose.material3.TextButton(
                        onClick = onAddItem,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.ADD)) {
                          Text("＋ " + stringResource(R.string.staging_add_item))
                        }
                  }
                  state.purchasePreview
                      ?.totalCents
                      ?.takeUnless { continueToSharing }
                      ?.let {
                        Text(
                            stringResource(R.string.purchase_total, Prices.format(it)),
                            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                            fontWeight = FontWeight.Bold)
                      }
                  statusContent()
                }
          }
        }
        if (continueToSharing) {
          if (onAddItem != null && !savedPurchase) {
            androidx.compose.material3.TextButton(
                onClick = onAddItem,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.ADD)) {
                  Text("＋ " + stringResource(R.string.staging_add_item))
                }
          }
          statusContent()
        }
        Spacer(Modifier.height(if (continueToSharing) 24.dp else Dimens.spacingM))
        if (continueToSharing) {
          state.purchasePreview?.totalCents?.let {
            Text(
                stringResource(R.string.purchase_total, Prices.format(it)),
                modifier = Modifier.padding(bottom = 8.dp).testTag(StagingTestTags.TOTAL),
                fontWeight = FontWeight.Bold)
          }
        }
        if (showMissingPrices) {
          Text(
              stringResource(R.string.staging_missing_prices),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.testTag("staging_missing_prices"),
          )
        }
        if (!savedPurchase || continueToSharing)
            Button(
                shape = RoundedCornerShape(14.dp),
                onClick = { if (state.hasMissingPrices) showMissingPrices = true else onConfirm() },
                enabled =
                    (state.items.isNotEmpty() && state.draft == null && actionsEnabled) ||
                        savedPurchase,
                modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.CONFIRM),
            ) {
              Text(
                  stringResource(
                      if (continueToSharing) R.string.receipt_continue
                      else if (state.purchase != null) R.string.purchase_save_inventory_only
                      else R.string.staging_confirm))
            }
      }
}

@Composable
private fun ReceiptDeleteActions(onDeleteReceipt: (() -> Unit)?) {
  if (onDeleteReceipt == null) return
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  var confirmOpen by rememberSaveable { mutableStateOf(false) }
  Box {
    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("receipt_more")) {
      Icon(
          Icons.Outlined.MoreVert,
          contentDescription = stringResource(R.string.receipt_more_actions))
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        modifier = Modifier.background(androidx.compose.ui.graphics.Color.White)) {
          DropdownMenuItem(
              text = {
                Text(
                    stringResource(R.string.receipt_delete_receipt),
                    color = MaterialTheme.colorScheme.error)
              },
              onClick = {
                menuOpen = false
                confirmOpen = true
              },
              modifier = Modifier.testTag("receipt_delete"))
        }
  }
  if (confirmOpen)
      AlertDialog(
          containerColor = androidx.compose.ui.graphics.Color.White,
          onDismissRequest = { confirmOpen = false },
          text = { Text(stringResource(R.string.receipt_delete_confirmation)) },
          confirmButton = {
            TextButton(
                onClick = {
                  confirmOpen = false
                  onDeleteReceipt()
                }) {
                  Text(
                      stringResource(R.string.receipt_delete_confirm),
                      color = MaterialTheme.colorScheme.error)
                }
          },
          dismissButton = {
            TextButton(onClick = { confirmOpen = false }) {
              Text(stringResource(R.string.receipt_cancel))
            }
          })
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
    enabled: Boolean,
    compact: Boolean = false,
) {
  if (compact) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(StagingTestTags.item(item.id))) {
          Row(
              Modifier.fillMaxWidth().padding(vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    Modifier.weight(1f),
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "×${item.quantity}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp))
                Text(
                    item.priceCents?.let {
                      stringResource(R.string.staging_price, Prices.format(it))
                    } ?: stringResource(R.string.staging_no_price),
                    fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.testTag(StagingTestTags.delete(item.id))) {
                      Icon(
                          Icons.Outlined.Delete,
                          stringResource(R.string.staging_delete_item, item.name),
                          tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
              }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    return
  }
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
    enabled: Boolean,
) {
  Card(
      // Keep the card content fully opaque when editing is unavailable. Material's disabled
      // Card applies disabled alpha to every child, which makes receipt names and prices
      // unreadable.
      onClick = { if (enabled) onClick() },
      modifier = Modifier.fillMaxWidth().testTag(StagingTestTags.item(item.id)),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
          // The action remains a no-op when editing is unavailable, while the icon stays visible.
          onClick = { if (enabled) onDelete() },
          modifier = Modifier.testTag(StagingTestTags.delete(item.id)),
      ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.staging_delete_item, item.name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
