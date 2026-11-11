// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.android.sharemate.model.user.FirebasePseudoRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies pseudo claims against the Firestore emulator and production security rules. */
@RunWith(AndroidJUnit4::class)
class FirestorePseudoRepositoryTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = FirestorePseudoRepository(firestore)

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() {
    FirebaseEmulator.clear()
  }

  @Test
  fun claimsAreCaseInsensitiveAndIdempotentForTheirOwner() = runBlocking {
    val firstUid = FirebaseEmulator.signInAs("first")
    repository.claim(firstUid, "Alex")
    repository.claim(firstUid, "  ALEX  ")

    val secondUid = FirebaseEmulator.signInAs("second")
    try {
      repository.claim(secondUid, "alex")
      fail("A case-insensitive duplicate claim must be rejected")
    } catch (failure: AuthException) {
      assertEquals(AuthError.PSEUDO_TAKEN, failure.error)
    }

    val claims = firestore.collection(FirestoreCollections.PSEUDO_CLAIMS)
    val listingFailure =
        assertThrows(FirebaseFirestoreException::class.java) {
          runBlocking { claims.get().await() }
        }
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, listingFailure.code)
  }

  @Test
  fun concurrentClaimsAllowOnlyOneOwner() = runBlocking {
    val firstUid = FirebaseEmulator.signInAs("first")
    val secondAuth = FirebaseEmulator.newSignedInApp("second")
    try {
      val otherRepository = FirestorePseudoRepository(secondAuth.firestore)
      val results =
          listOf(
                  async { claimResult(repository, firstUid, "Shared Name") },
                  async { claimResult(otherRepository, secondAuth.uid, "shared name") })
              .awaitAll()

      assertEquals(1, results.count { it == null })
      assertEquals(1, results.count { it == AuthError.PSEUDO_TAKEN })
    } finally {
      secondAuth.app.delete()
    }
  }

  @Test
  fun releasingAnOwnedClaimAllowsAnotherUserToTakeIt() = runBlocking {
    val uid = FirebaseEmulator.signInAs("first")
    repository.claim(uid, "Former Name")
    repository.release(uid, "Former Name")

    val secondUid = FirebaseEmulator.signInAs("second")
    repository.claim(secondUid, "Former Name")
  }

  @Test
  fun renamingPublicPseudoLetsAnotherUserClaimThePreviousName() = runBlocking {
    val uid = FirebaseEmulator.signInAs("first")
    val publicPseudos = FirebasePseudoRepository(firestore)
    publicPseudos.setPseudo(uid, "Former Name")
    publicPseudos.setPseudo(uid, "New Name")

    val secondUid = FirebaseEmulator.signInAs("second")
    repository.claim(secondUid, "former name")
    try {
      repository.claim(secondUid, "new name")
      fail("The new pseudo must remain reserved")
    } catch (failure: AuthException) {
      assertEquals(AuthError.PSEUDO_TAKEN, failure.error)
    }
  }

  @Test
  fun releaseKeepsAClaimStillUsedByThePublicProfile() = runBlocking {
    val uid = FirebaseEmulator.signInAs("first")
    repository.claim(uid, "Current Name")
    firestore
        .collection(FirestoreCollections.PROFILES)
        .document(uid)
        .set(mapOf(UserProfileFields.DISPLAY_NAME to "Current Name"))
        .await()

    repository.release(uid, "Current Name")

    val secondUid = FirebaseEmulator.signInAs("second")
    try {
      repository.claim(secondUid, "current name")
      fail("A pseudo still published in a profile must remain reserved")
    } catch (failure: AuthException) {
      assertEquals(AuthError.PSEUDO_TAKEN, failure.error)
    }
  }

  private suspend fun claimResult(
      target: PseudoRepository,
      uid: String,
      pseudo: String,
  ): AuthError? =
      try {
        target.claim(uid, pseudo)
        null
      } catch (failure: AuthException) {
        failure.error
      }
}
