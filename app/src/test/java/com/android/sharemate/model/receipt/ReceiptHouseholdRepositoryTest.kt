// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReceiptHouseholdRepositoryTest {
  @Test
  fun failedUploadKeepsMembershipAndLocalHistory() = runTest {
    val delegate = InMemoryHouseholdRepository()
    var evicted = false
    val repository =
        ReceiptHouseholdRepository(
            delegate,
            { _, _ -> evicted = true },
            {},
            beforeLeave = { _, _ -> error("Upload unavailable") })
    val home = repository.createHousehold("Home", "alice")
    assertTrue(runCatching { repository.leaveHousehold("alice") }.isFailure)
    assertFalse(evicted)
    assertEquals(home, delegate.getHouseholdForUser("alice"))
  }

  @Test
  fun successfulDepartureEvictsLocallyAndRejoinReactivatesSameHousehold() = runTest {
    val delegate = InMemoryHouseholdRepository()
    val evictions = mutableListOf<Pair<String, String>>()
    val activations = mutableListOf<String>()
    val repository =
        ReceiptHouseholdRepository(
            delegate, { user, home -> evictions.add(user to home) }, { activations.add(it) })
    val home = repository.createHousehold("Home", "alice")
    repository.leaveHousehold("alice")
    assertEquals(listOf("alice" to home.id), evictions)
    assertNotNull(delegate.getHousehold(home.id))
    repository.joinHousehold(home.inviteCode, "alice")
    assertEquals(listOf(home.id, home.id), activations)
  }

  @Test
  fun rejectedPrivateDepartureDoesNotEvictOrChangePolicy() = runTest {
    val delegate = InMemoryHouseholdRepository()
    var evicted = false
    val repository = ReceiptHouseholdRepository(delegate, { _, _ -> evicted = true }, {})
    repository.createHousehold("Private", "alice", FridgeType.PRIVATE)
    assertTrue(runCatching { repository.leaveHousehold("alice") }.isFailure)
    assertFalse(evicted)
    assertNotNull(delegate.getHouseholdForUser("alice"))
  }
}
