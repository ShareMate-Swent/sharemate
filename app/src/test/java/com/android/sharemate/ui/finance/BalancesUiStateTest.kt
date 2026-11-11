// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import org.junit.Assert.*
import org.junit.Test

class BalancesUiStateTest {
  @Test
  fun onlySignedInSharedHouseholdMembersCanViewBalances() {
    val home = Household(id = "home", memberIds = listOf("alice", "bob"))
    assertTrue(home.canViewBalances("alice"))
    assertTrue(home.canViewBalances("bob"))
    assertFalse(home.copy(type = FridgeType.PRIVATE).canViewBalances("alice"))
    assertFalse(home.canViewBalances(null))
    assertFalse(home.canViewBalances("outsider"))
    assertFalse((null as Household?).canViewBalances("alice"))
  }
}
