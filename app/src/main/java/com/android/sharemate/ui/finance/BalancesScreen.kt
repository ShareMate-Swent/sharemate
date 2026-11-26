// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.model.finance.Debt
import com.android.sharemate.model.finance.FinanceAmounts
import com.android.sharemate.ui.components.AppBackButton
import com.android.sharemate.ui.components.AppCard
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.theme.Dimens
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Stable semantics identifiers for balance rendering and interaction tests. */
object BalancesTestTags {
  /** Entry button inside Receipts, visible only for eligible shared members. */
  const val ENTRY = "receipts_balances"
  /** Returns to the Receipts dashboard. */
  const val BACK = "balances_back"
  /** Initial progress indicator. */
  const val LOADING = "balances_loading"
  /** Current member�s formatted net balance. */
  const val SUMMARY = "balances_summary"
  /** Retry action after observation failure or a server timeout. */
  const val RETRY = "balances_retry"
  const val SETTLEMENT_AMOUNT = "settlement_amount"
  const val SETTLEMENT_CONFIRM = "settlement_confirm"
}

/**
 * Renders financial state without reading profiles or accessing persistence.
 *
 * [onBack] adds navigation to the parent screen and [feedback] shows settlement status above the
 * balance details.
 */
@Composable
fun BalancesScreen(
    state: BalancesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSettle: ((Debt) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    feedback: (@Composable () -> Unit)? = null,
) {
  val locale = LocalConfiguration.current.locales[0]
  val colors = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  Column(modifier.fillMaxSize().background(colors.background)) {
    Row(
        modifier =
            Modifier.padding(start = Dimens.spacingM, top = Dimens.spacingM, end = Dimens.spacingM),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onBack != null) {
        AppBackButton(
            onBack = onBack,
            testTag = BalancesTestTags.BACK,
            contentDescription = stringResource(R.string.balances_back))
      }
      Text(
          stringResource(R.string.balances_title),
          Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
          color = colors.onBackground,
          style = typography.headlineMedium)
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(Dimens.spacingM),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingS),
    ) {
      if (feedback != null) item { feedback() }
      when (state) {
        BalancesUiState.Loading ->
            item {
              CircularProgressIndicator(
                  Modifier.padding(vertical = Dimens.spacingS).testTag(BalancesTestTags.LOADING))
            }
        BalancesUiState.Unavailable ->
            item { BalanceMessage(stringResource(R.string.balances_unavailable)) }
        BalancesUiState.InvalidData ->
            item { BalanceMessage(stringResource(R.string.balances_invalid_data)) }
        BalancesUiState.Offline,
        BalancesUiState.Error ->
            item {
              Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
                BalanceMessage(
                    stringResource(
                        if (state == BalancesUiState.Offline) R.string.balances_offline
                        else R.string.balances_error))
                AppPrimaryButton(
                    text = stringResource(R.string.household_retry),
                    onClick = onRetry,
                    modifier = Modifier.testTag(BalancesTestTags.RETRY))
              }
            }
        is BalancesUiState.Ready -> {
          item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
              Column(
                  Modifier.padding(Dimens.cardPadding),
                  verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                    Text(
                        stringResource(R.string.balances_your_balance),
                        style = typography.labelLarge)
                    val cents = state.summary.netBalancesCents[state.currentUserId] ?: 0L
                    val amount = formatFinanceAmount(cents, locale)
                    Text(
                        if (cents > 0) stringResource(R.string.balances_positive_amount, amount)
                        else amount,
                        Modifier.testTag(BalancesTestTags.SUMMARY),
                        style = typography.headlineMedium)
                  }
            }
          }
          item {
            Text(
                stringResource(R.string.balances_debts),
                color = colors.onBackground,
                style = typography.titleMedium)
          }
          if (state.summary.debts.isEmpty()) {
            item { BalanceMessage(stringResource(R.string.balances_empty)) }
          }
          items(state.summary.debts) { debt ->
            AppCard {
              Column(
                  modifier = Modifier.padding(vertical = Dimens.cardPadding),
                  verticalArrangement = Arrangement.spacedBy(Dimens.spacingS),
              ) {
                Text(
                    if (debt.debtorId == state.currentUserId)
                        stringResource(
                            R.string.balances_you_owe,
                            memberLabel(debt.creditorId, state.currentUserId, state.memberNames),
                            formatFinanceAmount(debt.amountCents, locale))
                    else
                        stringResource(
                            R.string.balances_debt,
                            memberLabel(debt.debtorId, state.currentUserId, state.memberNames),
                            memberLabel(debt.creditorId, state.currentUserId, state.memberNames),
                            formatFinanceAmount(debt.amountCents, locale)),
                    style = typography.titleMedium)
                if (debt.debtorId == state.currentUserId && onSettle != null) {
                  AppPrimaryButton(
                      text = stringResource(R.string.settlement_action),
                      onClick = { onSettle(debt) })
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BalanceMessage(message: String) {
  AppCard {
    Text(
        message,
        modifier = Modifier.padding(vertical = Dimens.cardPadding),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun memberLabel(userId: String, currentUserId: String, names: Map<String, String>): String =
    if (userId == currentUserId) stringResource(R.string.balances_you)
    else names[userId] ?: stringResource(R.string.balances_unknown_member)

/** Formats exact integer cents as CHF for the supplied locale without floating-point rounding. */
internal fun formatFinanceAmount(cents: Long, locale: Locale): String =
    NumberFormat.getCurrencyInstance(locale)
        .apply {
          currency = Currency.getInstance(FinanceAmounts.CURRENCY_CODE)
          minimumFractionDigits = FinanceAmounts.DECIMAL_SCALE
          maximumFractionDigits = FinanceAmounts.DECIMAL_SCALE
        }
        .format(BigDecimal.valueOf(cents, FinanceAmounts.DECIMAL_SCALE))
