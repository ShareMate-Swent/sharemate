// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HouseholdExpenseRepositoryEmulatorTest {
  private val firestore = FirebaseEmulator.firestore
  private val households = HouseholdRepositoryFirestore(firestore)
  private val repository = FirestoreHouseholdExpenseRepository(firestore)

  @Before fun setUp() = FirebaseEmulator.clear()

  @After fun tearDown() = FirebaseEmulator.clear()

  @Test
  fun clientsCannotCreateUpdateOrDeleteExpenses() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val home = households.createHousehold("Home", owner)
    seed(home)
    val reference = expenses(home.id).document("expense")
    val validExpense =
        mapOf(
            "id" to "new",
            "householdId" to home.id,
            "paidBy" to owner,
            "totalAmountCents" to 100L,
            "shares" to mapOf(owner to 100L))
    for (write in
        listOf<() -> com.google.android.gms.tasks.Task<Void>>(
            { expenses(home.id).document("new").set(validExpense) },
            {
              reference.update(
                  mapOf("totalAmountCents" to 200L, "shares" to mapOf("former-member" to 200L)))
            },
            { reference.delete() })) {
      val failure = runCatching { write().await() }.exceptionOrNull()
      assertEquals(
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
          (failure as? FirebaseFirestoreException)?.code)
    }
    assertEquals(100L, reference.get(Source.SERVER).await().getLong("totalAmountCents"))
    assertFalse(expenses(home.id).document("new").get(Source.SERVER).await().exists())
  }

  @Test
  fun sharedMembersObserveScopedEmptyAndAdminPublishedExpensesIncludingHistoricalParties() =
      runBlocking {
        val owner = FirebaseEmulator.signInAs("owner")
        val home = households.createHousehold("Home", owner)
        val other = households.createHousehold("Other", owner)
        seed(other, "foreign")
        val initial = CompletableDeferred<Unit>()
        val observed = async {
          repository
              .observeHouseholdExpenses(home.id)
              .onEach { initial.complete(Unit) }
              .take(2)
              .toList()
        }
        withTimeout(TIMEOUT_MS) { initial.await() }
        seed(home)
        val lists = withTimeout(TIMEOUT_MS) { observed.await() }
        assertTrue(lists.first().isEmpty())
        assertEquals(listOf("expense"), lists.last().map { it.id })
        assertEquals("former-member", lists.last().single().paidBy)
        val member = FirebaseEmulator.signInAs("member")
        households.joinHousehold(home.inviteCode, member)
        assertEquals(lists.last(), read(home.id))
      }

  @Test
  fun outsidersForgedProfilesAndSignedOutUsersCannotReadPopulatedOrEmptyQueries() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val populated = households.createHousehold("Populated", owner)
    val empty = households.createHousehold("Empty", owner)
    seed(populated)
    val outsider = FirebaseEmulator.signInAs("outsider")
    firestore
        .collection(FirestoreCollections.USERS)
        .document(outsider)
        .set(mapOf("householdId" to populated.id))
        .await()
    for (home in listOf(populated, empty)) assertDeniedRead(home.id)
    FirebaseEmulator.signOut()
    for (home in listOf(populated, empty)) assertDeniedRead(home.id)
  }

  @Test
  fun privateOwnersAndMissingHouseholdsHaveNoExpenseReadAccess() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val home = households.createHousehold("Private", owner, FridgeType.PRIVATE)
    seed(home)
    assertDeniedRead(home.id)
    assertDeniedRead("missing")
  }

  @Test
  fun malformedCommittedRecordFailsTheWholeObservationInsteadOfOmittingItsAmount() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val home = households.createHousehold("Home", owner)
    seed(home, "valid")
    seed(home, "invalid", sharesAmount = 99)
    val failure = runCatching { read(home.id) }.exceptionOrNull()
    assertTrue("Expected invalid financial data, got $failure", failure is IllegalArgumentException)
  }

  @Test
  fun leavingRevokesAlreadyActiveEmptyAndPopulatedObservations() = runBlocking {
    for (populated in listOf(false, true)) {
      val owner = FirebaseEmulator.signInAs("owner")
      val home = households.createHousehold("Home", owner)
      if (populated) seed(home)
      val member = FirebaseEmulator.signInAs("member")
      households.joinHousehold(home.inviteCode, member)
      val initial = CompletableDeferred<List<SharedExpense>>()
      val observation = async {
        runCatching {
              repository.observeHouseholdExpenses(home.id).collect { initial.complete(it) }
            }
            .exceptionOrNull()
      }
      try {
        assertEquals(populated, withTimeout(TIMEOUT_MS) { initial.await() }.isNotEmpty())
        households.leaveHousehold(member)
        val failure = withTimeout(TIMEOUT_MS) { observation.await() }
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (failure as? FirebaseFirestoreException)?.code)
      } finally {
        observation.cancelAndJoin()
      }
    }
  }

  private suspend fun read(householdId: String) =
      withTimeout(TIMEOUT_MS) { repository.observeHouseholdExpenses(householdId).first() }

  private suspend fun assertDeniedRead(householdId: String) {
    val failure = runCatching { read(householdId) }.exceptionOrNull()
    assertEquals(
        "Expected denied query for $householdId, got $failure",
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure as? FirebaseFirestoreException)?.code)
  }

  private fun expenses(householdId: String) =
      firestore
          .collection(FirestoreCollections.HOUSEHOLDS)
          .document(householdId)
          .collection(FirestoreCollections.EXPENSES)

  private fun seed(home: Household, id: String = "expense", sharesAmount: Long = 100) {
    val project = FirebaseApp.getInstance().options.projectId
    val body =
        """{"fields":{"id":{"stringValue":"$id"},"householdId":{"stringValue":"${home.id}"},"paidBy":{"stringValue":"former-member"},"totalAmountCents":{"integerValue":"100"},"shares":{"mapValue":{"fields":{"former-member":{"integerValue":"$sharesAmount"}}}}}}"""
    val connection =
        URL(
                "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}" +
                    "/v1/projects/$project/databases/(default)/documents/households/${home.id}/expenses/$id")
            .openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TIMEOUT_MS.toInt()
      connection.readTimeout = TIMEOUT_MS.toInt()
      connection.requestMethod = "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.doOutput = true
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      assertEquals(200, connection.responseCode)
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    const val TIMEOUT_MS = 10_000L
  }
}
