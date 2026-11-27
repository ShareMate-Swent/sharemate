// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppBackButton
import com.android.sharemate.ui.finance.BalancesTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.theme.ShareMateTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ReceiptExitDialog(
    busy: Boolean,
    failed: Boolean,
    title: String = stringResource(R.string.receipt_exit_title),
    saveLabel: String = stringResource(R.string.receipt_save_draft),
    discardLabel: String = stringResource(R.string.receipt_discard),
    onSaveDraft: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = { if (!busy) onDismiss() },
      title = { Text(title) },
      text = {
        if (failed)
            Text(
                stringResource(R.string.receipt_exit_failed),
                color = MaterialTheme.colorScheme.error)
      },
      confirmButton = {
        TextButton(
            onClick = onSaveDraft,
            enabled = !busy,
            modifier = Modifier.testTag(ReceiptDashboardTestTags.SAVE_DRAFT)) {
              Text(saveLabel)
            }
      },
      dismissButton = {
        TextButton(
            onClick = onDiscard,
            enabled = !busy,
            modifier = Modifier.testTag(ReceiptDashboardTestTags.DISCARD)) {
              Text(discardLabel)
            }
      })
}

@Composable
internal fun ReceiptSyncFeedback(
    state: InventorySaveState?,
    fallback: InventorySavePhase,
    editFailed: Boolean,
    onRetry: () -> Unit
) {
  val phase = state?.phase ?: fallback
  val message =
      when {
        editFailed ||
            phase in setOf(InventorySavePhase.FAILED, InventorySavePhase.RECOVERY_FAILED) ->
            R.string.receipt_save_changes_failed
        phase in setOf(InventorySavePhase.LOADING, InventorySavePhase.SUBMITTING) ->
            R.string.receipt_sync_saving
        phase == InventorySavePhase.PENDING -> R.string.receipt_sync_pending
        else -> null
      }
  if (message != null)
      Row(
          Modifier.fillMaxWidth().padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(message),
                Modifier.weight(1f).testTag(InventorySaveTestTags.STATUS),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (phase !in setOf(InventorySavePhase.LOADING, InventorySavePhase.SUBMITTING)) {
              TextButton(
                  onClick = onRetry, modifier = Modifier.testTag(InventorySaveTestTags.RETRY)) {
                    Text(stringResource(R.string.receipt_sync_retry))
                  }
            }
          }
}

@Composable
/** Displays the completed portion of the three-step receipt workflow. */
fun ReceiptProgress(completed: Int) {
  Row(
      Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(completed.toFloat(), 0f..3f, 2)
      },
      horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
          Box(
              Modifier.weight(1f)
                  .height(5.dp)
                  .background(
                      if (index < completed) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant,
                      RoundedCornerShape(5.dp)))
        }
      }
}

@Composable
/** Centers secondary-page titles independently of back navigation and trailing actions. */
fun ReceiptHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    backTag: String = ReceiptDashboardTestTags.BACK,
    actions: @Composable (() -> Unit)? = null,
) {
  Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
    if (onBack != null) AppBackButton(onBack, backTag, Modifier.align(Alignment.CenterStart))
    Text(
        title,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            if (onBack == null)
                Modifier.align(Alignment.CenterStart).testTag(NavigationTestTags.PAGE_CONTENT)
            else Modifier)
    actions?.let { content -> Box(Modifier.align(Alignment.CenterEnd)) { content() } }
  }
}

@Composable
internal fun ReceiptDashboard(
    receipts: List<RetainedReceipt>,
    showBalances: Boolean,
    error: Boolean,
    onScan: () -> Unit,
    onBalances: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onPurchase: (() -> Unit)?,
    balance: Long? = null,
    online: Boolean? = null,
) {
  val colors = MaterialTheme.colorScheme
  val status = ShareMateTheme.statusColors
  var filter by rememberSaveable { mutableIntStateOf(0) }
  var limit by rememberSaveable { mutableIntStateOf(RECEIPT_PAGE_SIZE) }
  val ordered = receipts.sortedByDescending { it.details?.scannedAtMillis ?: 0L }
  val filtered =
      ordered.filter {
        when (filter) {
          1 -> it.phase == InventorySavePhase.CONFIRMED
          2 -> it.submission.draft
          else -> true
        }
      }
  LazyColumn(
      Modifier.fillMaxSize()
          .background(colors.background)
          .testTag(ReceiptDashboardTestTags.HISTORY_LIST),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ReceiptHeader(stringResource(R.string.receipts_title)) }
        item {
          Button(
              onClick = onScan,
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_receipt), fontWeight = FontWeight.Bold)
              }
        }
        if (showBalances)
            item {
              Surface(
                  onClick = onBalances,
                  shape = RoundedCornerShape(16.dp),
                  color = colors.primaryContainer,
                  modifier = Modifier.fillMaxWidth().testTag(BalancesTestTags.ENTRY)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                      Column(Modifier.weight(1f)) {
                        Text(
                            if (balance == null) stringResource(R.string.receipt_balance_loading)
                            else if (balance == 0L) stringResource(R.string.receipt_balance_settled)
                            else
                                stringResource(
                                    if (balance > 0L) R.string.receipt_balance_credit
                                    else R.string.receipt_balance_owe,
                                    java.math.BigDecimal.valueOf(balance, 2).abs().toPlainString()),
                            fontWeight = FontWeight.Bold,
                            color =
                                when {
                                  balance != null && balance < 0L -> colors.error
                                  balance != null && balance > 0L -> status.fresh.content
                                  else -> colors.onSurface
                                })
                      }
                      Text(
                          stringResource(R.string.receipt_view_balances),
                          color = colors.primary,
                          fontSize = 12.sp)
                      Icon(
                          Icons.AutoMirrored.Filled.ArrowForward,
                          contentDescription = null,
                          tint = colors.primary)
                    }
                  }
            }
        item {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    R.string.receipts_title,
                    R.string.receipt_status_saved,
                    R.string.receipt_status_draft)
                .forEachIndexed { index, label ->
                  val count =
                      when (index) {
                        1 -> receipts.count { it.phase == InventorySavePhase.CONFIRMED }
                        2 -> receipts.count { it.submission.draft }
                        else -> receipts.size
                      }
                  Surface(
                      onClick = {
                        filter = index
                        limit = RECEIPT_PAGE_SIZE
                      },
                      modifier =
                          Modifier.weight(1f)
                              .semantics { selected = filter == index }
                              .testTag(ReceiptDashboardTestTags.filter(index)),
                      color = if (filter == index) colors.primaryContainer else colors.surface,
                      shape = RoundedCornerShape(12.dp),
                      border =
                          BorderStroke(
                              1.dp,
                              if (filter == index) colors.primary else colors.outlineVariant)) {
                        Column(Modifier.padding(12.dp)) {
                          Text(
                              count.toString(),
                              fontSize = 22.sp,
                              fontWeight = FontWeight.Bold,
                              color = colors.onSurface)
                          Text(
                              stringResource(label),
                              fontSize = 12.sp,
                              color = colors.onSurfaceVariant)
                        }
                      }
                }
          }
        }
        if (error)
            item {
              TextButton(onClick = onRetry) { Text(stringResource(R.string.receipt_edit_failed)) }
            }
        if (filtered.isEmpty())
            item {
              Text(
                  stringResource(
                      when (filter) {
                        1 -> R.string.receipt_empty_saved
                        2 -> R.string.receipt_empty_drafts
                        else -> R.string.receipt_empty_history
                      }),
                  color = colors.onSurfaceVariant,
                  modifier = Modifier.padding(vertical = 24.dp))
            }
        filtered
            .take(limit)
            .groupBy {
              it.details
                  ?.scannedAtMillis
                  ?.let { time -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(time)) }
                  .orEmpty()
            }
            .forEach { (date, rows) ->
              item {
                Text(
                    date,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = 12.dp))
              }
              items(rows, key = { it.submission.id }) { receipt ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          Modifier.width(20.dp).fillMaxHeight(),
                          contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.width(2.dp).fillMaxHeight().drawBehind {
                                  drawLine(
                                      colors.outlineVariant,
                                      Offset(size.width / 2, 0f),
                                      Offset(
                                          size.width / 2,
                                          size.height +
                                              if (receipt != rows.last()) 10.dp.toPx() else 0f),
                                      size.width)
                                })
                            Box(
                                Modifier.size(8.dp)
                                    .background(colors.primary, RoundedCornerShape(50)))
                          }
                      Surface(
                          onClick = { onOpen(receipt.submission.id) },
                          color = colors.surface,
                          shape = RoundedCornerShape(17.dp),
                          border = BorderStroke(1.dp, colors.outlineVariant),
                          modifier =
                              Modifier.weight(1f)
                                  .testTag(ReceiptDashboardTestTags.card(receipt.submission.id))) {
                            Column(Modifier.padding(14.dp)) {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                  Text(
                                      receipt.details?.title?.takeIf { it.isNotBlank() }
                                          ?: stringResource(R.string.receipt_untitled),
                                      fontWeight = FontWeight.Bold,
                                      color = colors.onSurface,
                                      maxLines = 2)
                                  Text(
                                      pluralStringResource(
                                          R.plurals.receipt_product_count,
                                          receipt.submission.items.size,
                                          receipt.submission.items.size),
                                      color = colors.onSurfaceVariant,
                                      fontSize = 12.sp)
                                }
                                val label = receiptBadgeLabel(receipt, online)
                                Text(
                                    stringResource(label),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        when {
                                          receipt.phase == InventorySavePhase.CONFIRMED ->
                                              status.fresh.content
                                          receipt.submission.draft -> status.expiringSoon.content
                                          else -> colors.primary
                                        },
                                    fontSize = 11.sp,
                                    modifier =
                                        Modifier.width(110.dp)
                                            .background(
                                                when {
                                                  receipt.phase == InventorySavePhase.CONFIRMED ->
                                                      status.fresh.container
                                                  receipt.submission.draft ->
                                                      status.expiringSoon.container
                                                  else -> colors.primaryContainer
                                                },
                                                RoundedCornerShape(50))
                                            .padding(horizontal = 9.dp, vertical = 5.dp))
                              }
                            }
                          }
                    }
              }
            }
        if (limit < filtered.size)
            item {
              TextButton(
                  onClick = { limit = nextReceiptLimit(limit, filtered.size) },
                  modifier = Modifier.fillMaxWidth().testTag(ReceiptDashboardTestTags.VIEW_MORE)) {
                    Text(stringResource(R.string.receipt_view_more))
                  }
            }
      }
}
