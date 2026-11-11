package com.android.sharemate.ui.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.android.sharemate.R
import com.android.sharemate.model.finance.Debt
import com.android.sharemate.model.finance.FinanceAmounts
import java.math.BigDecimal

/** Settlement presentation; persistence and payment guards belong to the ViewModel. */
@Composable
internal fun SettlementBalances(
    state: SettlementUiState,
    onRetry: () -> Unit,
    onSettle: (Debt, Long) -> Boolean,
    onRetrySettlement: () -> Unit,
) {
  var recipient by rememberSaveable { mutableStateOf<String?>(null) }
  val ready = state as? SettlementUiState.Ready
  val phase = ready?.submission?.phase
  val canSettle = phase == SettlementPhase.IDLE || phase == SettlementPhase.REJECTED
  val balances =
      when (state) {
        SettlementUiState.Loading -> BalancesUiState.Loading
        SettlementUiState.Unavailable -> BalancesUiState.Unavailable
        SettlementUiState.Error -> BalancesUiState.Error
        SettlementUiState.Offline -> BalancesUiState.Offline
        SettlementUiState.InvalidData -> BalancesUiState.InvalidData
        is SettlementUiState.Ready ->
            BalancesUiState.Ready(state.currentUserId, state.summary, state.memberNames)
      }
  Column {
    if (ready != null && !ready.isSynced) {
      Text(stringResource(R.string.settlement_cached_balances))
    }
    when (phase) {
      SettlementPhase.FAILED -> {
        Text(stringResource(R.string.settlement_uncertain))
        Button(onClick = onRetrySettlement) { Text(stringResource(R.string.household_retry)) }
      }
      SettlementPhase.REJECTED ->
          Text(
              stringResource(R.string.settlement_rejected), color = MaterialTheme.colorScheme.error)
      SettlementPhase.SUBMITTING,
      SettlementPhase.AWAITING_CONFIRMATION -> Text(stringResource(R.string.settlement_pending))
      else -> Unit
    }
    BalancesScreen(
        balances, onRetry, onSettle = if (canSettle) ({ recipient = it.creditorId }) else null)
  }
  val debt =
      ready?.summary?.debts?.firstOrNull {
        it.debtorId == ready.currentUserId && it.creditorId == recipient
      }
  if (ready != null && debt != null && canSettle) {
    SettleUpDialog(
        debt,
        ready.memberNames[debt.creditorId] ?: stringResource(R.string.balances_unknown_member),
        onDismiss = { recipient = null },
        onConfirm = { amount -> if (onSettle(debt, amount)) recipient = null })
  } else {
    LaunchedEffect(debt, canSettle) { recipient = null }
  }
}

/** Confirms recipient and amount; amounts use exact positive cents bounded by the current debt. */
@Composable
internal fun SettleUpDialog(
    debt: Debt,
    recipient: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
  var input by
      rememberSaveable(debt.creditorId, debt.amountCents) {
        mutableStateOf(BigDecimal.valueOf(debt.amountCents, 2).toPlainString())
      }
  val cents = FinanceAmounts.parseCents(input.replace(',', '.'))?.takeIf { it <= debt.amountCents }
  val locale = LocalConfiguration.current.locales[0]
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.settlement_action)) },
      text = {
        Column {
          Text(
              stringResource(
                  R.string.settlement_recipient,
                  recipient,
                  formatFinanceAmount(debt.amountCents, locale)))
          Text(stringResource(R.string.settlement_record_only))
          OutlinedTextField(
              value = input,
              onValueChange = { input = it },
              modifier = Modifier.testTag("settlement_amount"),
              singleLine = true,
              label = { Text(stringResource(R.string.settlement_amount)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              isError = cents == null,
              supportingText = {
                Text(
                    stringResource(
                        if (cents == null) R.string.settlement_invalid
                        else R.string.settlement_input_hint))
              })
        }
      },
      confirmButton = {
        TextButton(
            onClick = { cents?.let(onConfirm) },
            enabled = cents != null,
            modifier = Modifier.testTag("settlement_confirm")) {
              Text(stringResource(R.string.settlement_confirm))
            }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
