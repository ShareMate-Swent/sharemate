// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryHouseholdRepositoryTest {
  private val repository = InMemoryHouseholdRepository()

  @Test
  fun createHouseholdStoresCreatorAndCanBeFound() = runBlocking {
    val household = repository.createHousehold("Home", "creator")

    assertEquals(listOf("creator"), household.memberIds)
    assertEquals(household, repository.getHousehold(household.id))
    assertEquals(household, repository.getHouseholdForUser("creator"))
  }

  @Test
  fun joinHouseholdWithValidCodeAddsMember() = runBlocking {
    val created = repository.createHousehold("Home", "creator")

    val joined = repository.joinHousehold(created.inviteCode, "member")

    assertEquals(listOf("creator", "member"), joined.memberIds)
    assertEquals(joined, repository.getHouseholdForUser("member"))
  }

  @Test
  fun joinHouseholdWithInvalidCodeThrows() {
    runBlocking {
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.joinHousehold("invalid", "member") }
      }
    }
  }

  @Test
  fun joiningHouseholdTwiceThrows() {
    runBlocking {
      val created = repository.createHousehold("Home", "creator")
      repository.joinHousehold(created.inviteCode, "member")

      assertThrows(IllegalStateException::class.java) {
        runBlocking { repository.joinHousehold(created.inviteCode, "member") }
      }
    }
  }

  @Test
  fun unknownHouseholdAndUserReturnNull() = runBlocking {
    assertNull(repository.getHousehold("unknown"))
    assertNull(repository.getHouseholdForUser("unknown"))
  }
}
