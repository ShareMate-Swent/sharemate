// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.R
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.finance.HouseholdExpenseRepository
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.profile.UserProfileRepository

/** Navigation path owned by the Receipts tab. */
internal const val BALANCES_ROUTE = "receipts/balances"

/**
 * Receipts subpage for shared-household balances. The entry is hidden for private fridges. Personal
 * payment history belongs to a separate feature. The ViewModel checks access again before reading
 * expenses; a changed household type creates a fresh instance.
 */
@Composable
fun BalancesRoute(
    expenses: HouseholdExpenseRepository,
    households: com.android.sharemate.model.household.HouseholdRepository,
    auth: AuthRepository,
    household: Household,
    userId: String,
    profiles: UserProfileRepository,
    onBack: () -> Unit,
) {
  val model: BalancesViewModel =
      viewModel(
          key = "balances_${userId}_${household.id}_${household.type}",
          factory =
              viewModelFactory {
                initializer {
                  BalancesViewModel(expenses, households, auth, household.id, profiles = profiles)
                }
              })
  val state by model.state.collectAsStateWithLifecycle()
  Column {
    TextButton(onClick = onBack) { Text(stringResource(R.string.balances_back)) }
    BalancesScreen(state, model::retry)
  }
}
