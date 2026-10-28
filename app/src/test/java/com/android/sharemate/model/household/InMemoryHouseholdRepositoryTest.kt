// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
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
  fun privateHouseholdHasNoInviteCodeAndCannotBeJoined() = runBlocking {
    val household = repository.createHousehold("Mine", "owner", FridgeType.PRIVATE)
    assertEquals("", household.inviteCode)
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.joinHousehold(household.inviteCode, "outsider") }
    }
    assertEquals(household, repository.getHousehold(household.id))
    assertNull(repository.getHouseholdForUser("outsider"))
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
  fun joiningHouseholdTwiceIsIdempotent() {
    runBlocking {
      val created = repository.createHousehold("Home", "creator")
      val firstJoin = repository.joinHousehold(created.inviteCode, "member")

      val secondJoin = repository.joinHousehold(created.inviteCode, "member")

      assertEquals(firstJoin, secondJoin)
      assertEquals(listOf("creator", "member"), secondJoin.memberIds)
    }
  }

  @Test
  fun privateOwnerCannotLeaveOrLoseOtherMemberships() = runBlocking {
    val privateFridge = repository.createHousehold("Mine", "owner", FridgeType.PRIVATE)
    val sharedFridge = repository.createHousehold("Shared", "owner")
    assertThrows(PrivateHouseholdLeaveException::class.java) {
      runBlocking { repository.leaveHousehold("owner") }
    }
    assertEquals(privateFridge, repository.getHousehold(privateFridge.id))
    assertEquals(sharedFridge, repository.getHousehold(sharedFridge.id))
  }

  @Test
  fun leavingHouseholdRemovesOnlyThatMemberAndKeepsTheHousehold() = runBlocking {
    val created = repository.createHousehold("Home", "creator")
    repository.joinHousehold(created.inviteCode, "member")

    repository.leaveHousehold("member")

    assertNull(repository.getHouseholdForUser("member"))
    assertEquals(listOf("creator"), repository.getHousehold(created.id)?.memberIds)
  }

  @Test
  fun leavingWithoutHouseholdDoesNothingAndBlankUserThrows() {
    runBlocking {
      val created = repository.createHousehold("Home", "creator")

      repository.leaveHousehold("outsider")

      assertEquals(created, repository.getHousehold(created.id))
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.leaveHousehold(" ") }
      }
    }
  }

  @Test
  fun unknownHouseholdAndUserReturnNull() = runBlocking {
    assertNull(repository.getHousehold("unknown"))
    assertNull(repository.getHouseholdForUser("unknown"))
  }
}
