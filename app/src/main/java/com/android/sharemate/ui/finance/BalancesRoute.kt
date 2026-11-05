// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.finance.HouseholdExpenseRepository
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository

@Composable
fun BalancesRoute(
    expenses: HouseholdExpenseRepository,
    households: HouseholdRepository,
    auth: AuthRepository,
    household: Household,
    userId: String,
) {
  if (household.type != FridgeType.SHARED) {
    BalancesScreen(BalancesUiState.Unavailable, {})
  } else {
    val model: BalancesViewModel =
        viewModel(
            key = "balances_${userId}_${household.id}",
            factory =
                viewModelFactory {
                  initializer { BalancesViewModel(expenses, households, auth, household.id) }
                })
    val state by model.state.collectAsStateWithLifecycle()
    BalancesScreen(state, model::retry)
  }
}
