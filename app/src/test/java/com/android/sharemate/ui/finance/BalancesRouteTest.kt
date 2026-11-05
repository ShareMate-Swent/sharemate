// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.finance.HouseholdExpenseRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
class BalancesRouteTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var household by
      mutableStateOf(Household(id = "home", memberIds = listOf("alice", "bob")))
  private val requests = mutableListOf<String>()
  private val households =
      object : HouseholdRepository by InMemoryHouseholdRepository() {
        override suspend fun getHousehold(householdId: String): Household {
          requests += householdId
          return household.copy(id = householdId)
        }
      }
  private val auth =
      object : AuthRepository by mock(AuthRepository::class.java) {
        override val session = MutableStateFlow<AuthSession?>(AuthSession("bob", null))
      }
  private val observed = mutableListOf<String>()
  private val records = MutableStateFlow<List<SharedExpense>>(emptyList())
  private val expenses =
      object : HouseholdExpenseRepository {
        override fun observeHouseholdExpenses(householdId: String) =
            (if (householdId == "home") records else flowOf(emptyList())).also {
              observed += householdId
            }
      }

  @After fun clearModels() = compose.runOnIdle { compose.activity.viewModelStore.clear() }

  private fun show(source: HouseholdExpenseRepository?) {
    compose.setContent {
      MaterialTheme { BalancesRoute(source, households, auth, household, "bob") }
    }
  }

  @Test
  fun missingSourceShowsUnavailableIntegrationInsteadOfInventingZeroBalances() {
    show(null)
    compose.onNodeWithText("Shared expenses are not available yet.").assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    assertTrue(requests.isEmpty())
    assertTrue(observed.isEmpty())
  }

  @Test
  fun privateHouseholdDoesNotReadExpensesOrMembership() {
    household = household.copy(type = FridgeType.PRIVATE)
    show(expenses)
    compose.onNodeWithText("Balances are available for shared households.").assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    assertTrue(requests.isEmpty())
    assertTrue(observed.isEmpty())
  }

  @Test
  fun injectedReaderUpdatesTheScreenAndHouseholdReplacementClearsTheOldAmount() {
    records.value = listOf(SharedExpense("one", "home", "alice", 1500, mapOf("bob" to 1500L)))
    show(expenses)
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(BalancesTestTags.SUMMARY).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("15.00", substring = true)
    compose.onNodeWithText("You owe Member alice", substring = true).assertIsDisplayed()
    compose.runOnIdle { records.value = emptyList() }
    compose.waitUntil(5000) {
      compose.onAllNodesWithText("No outstanding debts.").fetchSemanticsNodes().isNotEmpty()
    }
    compose.runOnIdle {
      records.value = listOf(SharedExpense("one", "home", "alice", 1500, mapOf("bob" to 1500L)))
    }
    compose.waitUntil(5000) {
      compose
          .onAllNodesWithText("You owe Member alice", substring = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.runOnIdle {
      compose.activity.viewModelStore.clear()
      household = household.copy(id = "new-home")
    }
    compose.waitUntil(5000) {
      compose.onAllNodesWithText("No outstanding debts.").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("No outstanding debts.").assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("0.00", substring = true)
    compose.onNodeWithText("You owe Member alice", substring = true).assertDoesNotExist()
    assertEquals(listOf("home", "new-home"), requests)
    assertEquals(requests, observed)
  }
}
