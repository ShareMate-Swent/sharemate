// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTokenRegistrarTest {
  private class FakeAuthRepository(override val session: Flow<AuthSession?>) : AuthRepository {
    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

    override fun signOut() = Unit
  }

  private class CountingAuthRepository : AuthRepository {
    var subscriptions = 0

    override val session: Flow<AuthSession?> = flow {
      subscriptions++
      emit(null)
    }

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

    override fun signOut() = Unit
  }

  private class RecordingTokenRepository(
      private val fail: Boolean = false,
  ) : PushTokenRepository {
    val saved = mutableListOf<Triple<String, String, String>>()

    override suspend fun saveToken(userId: String, deviceId: String, token: String) {
      if (fail) throw IllegalStateException("boom")
      saved += Triple(userId, deviceId, token)
    }
  }

  private fun registrar(session: AuthSession?, tokenRepository: PushTokenRepository) =
      PushTokenRegistrar(FakeAuthRepository(flowOf(session)), tokenRepository, DEVICE_ID)

  @Test
  fun registersTheFetchedTokenForTheGivenUser() = runTest {
    val tokens = RecordingTokenRepository()

    registrar(null, tokens).registerCurrentToken("alice") { "token-1" }

    assertEquals(listOf(Triple("alice", DEVICE_ID, "token-1")), tokens.saved)
  }

  @Test
  fun registeringDoesNotReadTheAuthSession() = runTest {
    val auth = CountingAuthRepository()

    PushTokenRegistrar(auth, RecordingTokenRepository(), DEVICE_ID).registerCurrentToken("alice") {
      "token-1"
    }

    assertEquals(0, auth.subscriptions)
  }

  @Test
  fun aFailingFetchIsSwallowed() = runTest {
    val tokens = RecordingTokenRepository()

    registrar(null, tokens).registerCurrentToken("alice") {
      throw IllegalStateException("no token yet")
    }

    assertTrue(tokens.saved.isEmpty())
  }

  @Test
  fun aFailingSaveIsSwallowed() = runTest {
    val tokens = RecordingTokenRepository(fail = true)

    registrar(null, tokens).registerCurrentToken("alice") { "token-1" }
  }

  @Test
  fun onTokenRefreshedSavesTheGivenTokenForTheSignedInUser() = runTest {
    val tokens = RecordingTokenRepository()

    val outcome =
        registrar(AuthSession("bob", "bob@example.org"), tokens).onTokenRefreshed("token-2")

    assertEquals(listOf(Triple("bob", DEVICE_ID, "token-2")), tokens.saved)
    assertTrue(outcome)
  }

  @Test
  fun onTokenRefreshedDoesNothingWhenSignedOutAndAsksForNoRetry() = runTest {
    val tokens = RecordingTokenRepository()

    val outcome = registrar(null, tokens).onTokenRefreshed("token-2")

    assertTrue(tokens.saved.isEmpty())
    assertTrue(outcome)
  }

  @Test
  fun onTokenRefreshedReportsAFailedSaveSoItCanBeRetried() = runTest {
    val tokens = RecordingTokenRepository(fail = true)

    val outcome =
        registrar(AuthSession("bob", "bob@example.org"), tokens).onTokenRefreshed("token-2")

    assertFalse(outcome)
  }

  private companion object {
    const val DEVICE_ID = "device-1"
  }
}
