// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.model.finance.FinanceAmounts
import com.android.sharemate.ui.navigation.NavigationTestTags
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object BalancesTestTags {
  const val LOADING = "balances_loading"
  const val SUMMARY = "balances_summary"
  const val RETRY = "balances_retry"
}

private val ScreenPadding = 20.dp
private val ContentSpacing = 16.dp

/** Renders financial state without reading profiles or accessing persistence. */
@Composable
fun BalancesScreen(
    state: BalancesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val locale = LocalConfiguration.current.locales[0]
  LazyColumn(
      modifier = modifier.fillMaxSize().padding(ScreenPadding),
      verticalArrangement = Arrangement.spacedBy(ContentSpacing),
  ) {
    item {
      Text(
          stringResource(R.string.balances_title),
          Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
          style = MaterialTheme.typography.headlineSmall)
    }
    when (state) {
      BalancesUiState.Loading ->
          item { CircularProgressIndicator(Modifier.testTag(BalancesTestTags.LOADING)) }
      BalancesUiState.Unavailable -> item { Text(stringResource(R.string.balances_unavailable)) }
      BalancesUiState.Error ->
          item {
            Text(stringResource(R.string.balances_error), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.testTag(BalancesTestTags.RETRY)) {
              Text(stringResource(R.string.household_retry))
            }
          }
      is BalancesUiState.Ready -> {
        item {
          Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(ContentSpacing),
                verticalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                  Text(stringResource(R.string.balances_your_balance))
                  val cents = state.summary.netBalancesCents[state.currentUserId] ?: 0L
                  val amount = formatFinanceAmount(cents, locale)
                  Text(
                      if (cents > 0) stringResource(R.string.balances_positive_amount, amount)
                      else amount,
                      Modifier.testTag(BalancesTestTags.SUMMARY),
                      style = MaterialTheme.typography.headlineMedium)
                }
          }
        }
        item {
          Text(
              stringResource(R.string.balances_debts), style = MaterialTheme.typography.titleMedium)
        }
        if (state.summary.debts.isEmpty()) {
          item { Text(stringResource(R.string.balances_empty)) }
        }
        items(state.summary.debts) { debt ->
          Text(
              if (debt.debtorId == state.currentUserId)
                  stringResource(
                      R.string.balances_you_owe,
                      memberLabel(debt.creditorId, state.currentUserId),
                      formatFinanceAmount(debt.amountCents, locale))
              else
                  stringResource(
                      R.string.balances_debt,
                      memberLabel(debt.debtorId, state.currentUserId),
                      memberLabel(debt.creditorId, state.currentUserId),
                      formatFinanceAmount(debt.amountCents, locale)))
        }
      }
    }
  }
}

@Composable
private fun memberLabel(userId: String, currentUserId: String): String =
    if (userId == currentUserId) stringResource(R.string.balances_you)
    else stringResource(R.string.balances_member, userId)

internal fun formatFinanceAmount(cents: Long, locale: Locale): String =
    NumberFormat.getCurrencyInstance(locale)
        .apply {
          currency = Currency.getInstance(FinanceAmounts.CURRENCY_CODE)
          minimumFractionDigits = 2
          maximumFractionDigits = 2
        }
        .format(BigDecimal.valueOf(cents, 2))
