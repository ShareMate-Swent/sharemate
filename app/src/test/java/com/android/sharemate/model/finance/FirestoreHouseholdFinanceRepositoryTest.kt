// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreHouseholdFinanceRepositoryTest {
  private val db = mock(FirebaseFirestore::class.java)
  private val collection = mock(CollectionReference::class.java)
  private val parent = mock(DocumentReference::class.java)
  private val registration = mock(ListenerRegistration::class.java)
  private val writer = mock(SettlementRepository::class.java)
  private val queries = mutableMapOf<String, EventListener<QuerySnapshot>>()
  private lateinit var membership: EventListener<DocumentSnapshot>
  private lateinit var sync: Runnable
  private val repository = FirestoreHouseholdFinanceRepository(db, writer)
  private val payment = Settlement("s", "home", "bob", "alice", 600)

  init {
    `when`(db.collection("households")).thenReturn(collection)
    `when`(collection.document("home")).thenReturn(parent)
    `when`(parent.addSnapshotListener(eq(MetadataChanges.INCLUDE), any())).thenAnswer {
      membership = it.getArgument(1)
      registration
    }
    for (name in listOf("expenses", "settlements")) {
      val query = mock(CollectionReference::class.java)
      `when`(parent.collection(name)).thenReturn(query)
      `when`(query.addSnapshotListener(eq(MetadataChanges.INCLUDE), any())).thenAnswer {
        queries[name] = it.getArgument(1)
        registration
      }
    }
    `when`(db.addSnapshotsInSyncListener(any(Runnable::class.java))).thenAnswer {
      sync = it.getArgument(0)
      registration
    }
  }

  @Test
  fun cachedLedgerIsPublishedWithoutWaitingForServer() = runTest {
    val received = mutableListOf<HouseholdFinanceSnapshot>()
    val job = launch { repository.observeHouseholdFinances("home").collect { received += it } }
    runCurrent()
    membership.onEvent(parentSnapshot(cache = true), null)
    queries.getValue("expenses").onEvent(snapshot(cache = true), null)
    queries.getValue("settlements").onEvent(snapshot(listOf(document()), cache = true), null)
    sync.run()
    runCurrent()
    assertEquals(listOf(payment), received.single().settlements)
    assertFalse(received.single().isSynced)
    job.cancelAndJoin()
  }

  @Test
  fun publishesOnlyAtSyncBoundaryAndDelegatesWrites() = runTest {
    val received = mutableListOf<HouseholdFinanceSnapshot>()
    val job = launch { repository.observeHouseholdFinances("home").collect { received += it } }
    runCurrent()
    membership.onEvent(parentSnapshot(), null)
    queries.getValue("expenses").onEvent(snapshot(), null)
    sync.run()
    runCurrent()
    assertTrue(received.isEmpty())
    queries.getValue("settlements").onEvent(snapshot(listOf(document())), null)
    runCurrent()
    assertTrue(received.isEmpty())
    sync.run()
    runCurrent()
    assertEquals(listOf(payment), received.single().settlements)
    assertTrue(received.single().isSynced)
    sync.run()
    runCurrent()
    assertEquals(1, received.size)
    val expense = document()
    `when`(expense.data)
        .thenReturn(
            mapOf(
                "id" to "s",
                "householdId" to "home",
                "paidBy" to "alice",
                "totalAmountCents" to 1500L,
                "shares" to mapOf("bob" to 1500L)))
    queries.getValue("expenses").onEvent(snapshot(listOf(expense)), null)
    sync.run()
    runCurrent()
    assertEquals(1500L, received.last().expenses.single().totalAmountCents)
    `when`(writer.recordSettlement(payment)).thenReturn(payment)
    assertEquals(payment, repository.recordSettlement(payment))
    verify(writer).recordSettlement(payment)
    job.cancelAndJoin()
    verify(registration, times(4)).remove()
  }

  @Test
  fun emptyCacheAndUnconfirmedParentDoNotClaimAnEmptyServerLedgerOrDenyAccess() = runTest {
    for (parent in
        listOf(
            parentSnapshot(cache = true, exists = false),
            parentSnapshot(type = "PRIVATE", cache = true),
            parentSnapshot(pending = true))) {
      val received = mutableListOf<HouseholdFinanceSnapshot>()
      val job = launch { repository.observeHouseholdFinances("home").collect { received += it } }
      runCurrent()
      membership.onEvent(parent, null)
      queries.getValue("expenses").onEvent(snapshot(cache = true), null)
      queries.getValue("settlements").onEvent(snapshot(cache = true), null)
      sync.run()
      runCurrent()
      assertFalse(received.single().isSynced)
      assertTrue(received.single().expenses.isEmpty())
      assertTrue(received.single().settlements.isEmpty())
      assertTrue(job.isActive)
      job.cancelAndJoin()
    }
  }

  @Test
  fun cacheThenServerThenConnectionLossRetainsRecordsAndEmitsMetadataChanges() = runTest {
    val received = mutableListOf<HouseholdFinanceSnapshot>()
    val job = launch { repository.observeHouseholdFinances("home").collect { received += it } }
    runCurrent()
    membership.onEvent(parentSnapshot(cache = true), null)
    queries.getValue("expenses").onEvent(snapshot(cache = true), null)
    queries.getValue("settlements").onEvent(snapshot(listOf(document()), cache = true), null)
    sync.run()
    runCurrent()
    assertFalse(received.single().isSynced)
    membership.onEvent(parentSnapshot(), null)
    queries.getValue("expenses").onEvent(snapshot(), null)
    sync.run()
    runCurrent()
    assertEquals(1, received.size)
    queries.getValue("settlements").onEvent(snapshot(listOf(document())), null)
    sync.run()
    runCurrent()
    assertTrue(received.last().isSynced)
    queries.getValue("settlements").onEvent(snapshot(listOf(document()), cache = true), null)
    sync.run()
    runCurrent()
    assertEquals(listOf(false, true, false), received.map { it.isSynced })
    assertTrue(received.all { it.settlements == listOf(payment) })
    assertTrue(job.isActive)
    job.cancelAndJoin()
  }

  @Test
  fun eachCachedOrPendingSourceMakesTheLedgerProvisionalUntilAcknowledged() = runTest {
    for (source in listOf("parent", "expenses", "settlements")) {
      for (pending in listOf(false, true)) {
        val received = mutableListOf<HouseholdFinanceSnapshot>()
        val job = launch { repository.observeHouseholdFinances("home").collect { received += it } }
        runCurrent()
        membership.onEvent(parentSnapshot(), null)
        queries.getValue("expenses").onEvent(snapshot(), null)
        queries.getValue("settlements").onEvent(snapshot(), null)
        sync.run()
        runCurrent()
        if (source == "parent")
            membership.onEvent(parentSnapshot(cache = !pending, pending = pending), null)
        else queries.getValue(source).onEvent(snapshot(cache = !pending, pending = pending), null)
        sync.run()
        runCurrent()
        assertFalse(received.last().isSynced)
        if (source == "parent") membership.onEvent(parentSnapshot(), null)
        else queries.getValue(source).onEvent(snapshot(), null)
        sync.run()
        runCurrent()
        assertEquals(listOf(true, false, true), received.map { it.isSynced })
        job.cancelAndJoin()
      }
    }
  }

  @Test
  fun errorsMalformedRecordsAndRegistrationFailureReleaseListeners() = runTest {
    for (source in
        listOf(
            "parent",
            "parentError",
            "parentMissing",
            "parentNull",
            "expenses",
            "settlements",
            "expensesNull",
            "settlementsNull",
            "malformed",
            "register")) {
      clearInvocations(registration)
      val received = mutableListOf<HouseholdFinanceSnapshot>()
      var failure: Throwable? = null
      if (source == "register") {
        doThrow(IllegalStateException("closed"))
            .`when`(db)
            .addSnapshotsInSyncListener(any(Runnable::class.java))
      }
      val job = launch {
        repository
            .observeHouseholdFinances("home")
            .catch { failure = it }
            .collect { received += it }
      }
      runCurrent()
      val denied =
          FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
      when (source) {
        "parent" -> membership.onEvent(parentSnapshot(type = "PRIVATE"), null)
        "parentMissing" -> membership.onEvent(parentSnapshot(exists = false), null)
        "parentError" -> membership.onEvent(null, denied)
        "parentNull" -> membership.onEvent(null, null)
        "expenses",
        "settlements" -> queries.getValue(source).onEvent(null, denied)
        "expensesNull",
        "settlementsNull" -> queries.getValue(source.removeSuffix("Null")).onEvent(null, null)
        "malformed" -> {
          membership.onEvent(parentSnapshot(), null)
          queries.getValue("expenses").onEvent(snapshot(), null)
          queries
              .getValue("settlements")
              .onEvent(snapshot(listOf(document(mapOf("extra" to true)))), null)
          sync.run()
        }
      }
      runCurrent()
      job.join()
      assertNotNull(failure)
      assertTrue(received.isEmpty())
      verify(registration, times(if (source == "register") 3 else 4)).remove()
    }
  }

  @Test
  fun rejectsBadPathsAndSettlementFields() {
    for (id in listOf("", " ", "home/other")) {
      assertThrows(IllegalArgumentException::class.java) { repository.observeHouseholdFinances(id) }
    }
    for (fields in
        listOf(
            mapOf("id" to "other"),
            mapOf("householdId" to "other"),
            mapOf("amountCents" to "600"),
            mapOf("payerId" to null),
            mapOf("recipientId" to "bob"))) {
      assertThrows(IllegalArgumentException::class.java) { document(fields).toSettlement("home") }
    }
    verifyNoInteractions(db)
  }

  private fun metadata(cache: Boolean = false, pending: Boolean = false) =
      mock(SnapshotMetadata::class.java).also {
        `when`(it.isFromCache).thenReturn(cache)
        `when`(it.hasPendingWrites()).thenReturn(pending)
      }

  private fun parentSnapshot(
      type: String = "SHARED",
      cache: Boolean = false,
      pending: Boolean = false,
      exists: Boolean = true
  ): DocumentSnapshot {
    val metadata = metadata(cache, pending)
    return mock(DocumentSnapshot::class.java).also {
      `when`(it.metadata).thenReturn(metadata)
      `when`(it.exists()).thenReturn(exists)
      `when`(it.get("type")).thenReturn(type)
    }
  }

  private fun snapshot(
      documents: List<DocumentSnapshot> = emptyList(),
      cache: Boolean = false,
      pending: Boolean = false
  ): QuerySnapshot {
    val metadata = metadata(cache, pending)
    return mock(QuerySnapshot::class.java).also {
      `when`(it.metadata).thenReturn(metadata)
      `when`(it.documents).thenReturn(documents)
    }
  }

  private fun document(overrides: Map<String, Any?> = emptyMap()) =
      mock(DocumentSnapshot::class.java).also {
        `when`(it.id).thenReturn("s")
        `when`(it.data)
            .thenReturn(
                mapOf(
                    "id" to "s",
                    "householdId" to "home",
                    "payerId" to "bob",
                    "recipientId" to "alice",
                    "amountCents" to 600L) + overrides)
      }
}
