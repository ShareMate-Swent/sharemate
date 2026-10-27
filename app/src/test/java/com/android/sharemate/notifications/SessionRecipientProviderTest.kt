// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.InMemoryHouseholdRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRecipientProviderTest {
  private class FakeAuthRepository(override val session: Flow<AuthSession?>) : AuthRepository {
    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

    override fun signOut() = Unit
  }

  private val households = InMemoryHouseholdRepository()

  private fun provider(session: AuthSession?) =
      SessionRecipientProvider(FakeAuthRepository(flowOf(session)), households)

  @Test
  fun resolvesTheSignedInUsersHousehold() = runTest {
    val household: Household = households.createHousehold("Flat", "alice")

    val recipient = provider(AuthSession("alice", "alice@example.org")).current()

    assertEquals(NotificationRecipient("alice", household.id), recipient)
  }

  @Test
  fun hasNoRecipientWhenSignedOut() = runTest {
    households.createHousehold("Flat", "alice")

    assertNull(provider(null).current())
  }

  @Test
  fun hasNoRecipientWithoutAHousehold() = runTest {
    assertNull(provider(AuthSession("bob", "bob@example.org")).current())
  }
}
