// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.receipt.RECEIPT_INITIAL_RETRY_MILLIS
import com.android.sharemate.model.receipt.RECEIPT_MAX_RETRY_MILLIS
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudPermanentException
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptHistorySyncTest {
  @Test
  fun failedEntryRetriesAloneWithoutBlockingPeersOrReplayingSuccessfulWrites() = runTest {
    val bad = java.util.UUID.randomUUID().toString()
    val good = java.util.UUID.randomUUID().toString()
    val writes = mutableListOf<String>()
    var failSave = true
    var failDetails = true
    var caches = 0
    var subscriptions = 0
    val disk =
        object : InventorySubmissionStore by store {
          override suspend fun save(submission: InventorySubmission) {
            writes += submission.id
            if (submission.id == bad && failSave) throw IOException("Disk busy")
          }

          override suspend fun saveDetails(details: ReceiptDetails) {
            if (details.id == bad && failDetails) throw IOException("Metadata busy")
          }

          override suspend fun cacheShared(
              viewerId: String,
              householdId: String,
              entries: List<ReceiptCloudEntry>
          ) {
            caches++
          }
        }
    val entries =
        listOf(bad, good).map { id ->
          ReceiptCloudEntry(
              InventorySubmission(
                  id, "owner", "home", listOf(StagedItem("row", "Milk", 1, 100)), draft = true),
              ReceiptDetails(id, "owner", "home", "", 0))
        }
    val snapshots = MutableStateFlow(entries)
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(ownerId: String, householdId: String) = flow {
            subscriptions++
            emitAll(snapshots)
          }
        }
    val sync = ReceiptHistorySync(disk, cloud, "owner", "home")
    sync.start(backgroundScope) {}
    runCurrent()
    assertEquals(listOf(bad, good), writes)
    assertTrue(sync.error.value)
    failSave = false
    advanceTimeBy(RECEIPT_INITIAL_RETRY_MILLIS)
    runCurrent()
    assertTrue(sync.error.value)
    failDetails = false
    advanceTimeBy(RECEIPT_INITIAL_RETRY_MILLIS * 2)
    runCurrent()
    assertFalse(sync.error.value)
    assertEquals(listOf(bad, good, bad), writes)
    assertEquals(1, caches)
    assertEquals(1, subscriptions)
  }

  @Test
  fun deletionRaceAndCancellationDoNotBecomeRetryErrors() = runTest {
    for (cancelled in listOf(false, true)) {
      var deleted = false
      var attempts = 0
      val disk =
          object : InventorySubmissionStore by store {
            override suspend fun isDeleted(ownerId: String, householdId: String, id: String) =
                deleted

            override suspend fun save(submission: InventorySubmission) {
              attempts++
              if (cancelled) throw CancellationException("Leaving")
              deleted = true
              throw IllegalStateException("Receipt was deleted")
            }
          }
      val id = java.util.UUID.randomUUID().toString()
      val entry =
          ReceiptCloudEntry(
              InventorySubmission(id, "owner", "home", emptyList(), draft = true),
              ReceiptDetails(id, "owner", "home", "", 0))
      val cloud =
          object : ReceiptCloudRepository {
            override fun observe(ownerId: String, householdId: String) = flow {
              emit(listOf(entry))
              awaitCancellation()
            }
          }
      val sync = ReceiptHistorySync(disk, cloud, "owner", "home")
      val job = sync.start(backgroundScope) {}
      runCurrent()
      advanceTimeBy(RECEIPT_MAX_RETRY_MILLIS)
      runCurrent()
      assertEquals(1, attempts)
      assertFalse(sync.error.value)
      job.cancel()
    }
  }

  @Test
  fun permanentFailuresStopBothObserversAndKeepTheErrorVisible() = runTest {
    var attempts = 0
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(
              ownerId: String,
              householdId: String
          ): Flow<List<ReceiptCloudEntry>> {
            attempts++
            throw ReceiptCloudPermanentException(IllegalStateException("Access revoked"))
          }

          override fun observeDeleted(ownerId: String, householdId: String): Flow<Set<String>> {
            attempts++
            throw ReceiptCloudPermanentException(IllegalStateException("Access revoked"))
          }
        }
    val sync = ReceiptHistorySync(store, cloud, "owner", "home")
    val job = sync.start(backgroundScope) {}
    runCurrent()
    advanceTimeBy(RECEIPT_MAX_RETRY_MILLIS * 10)
    runCurrent()
    assertEquals(2, attempts)
    assertTrue(job.isCompleted)
    assertTrue(sync.error.value)
  }

  private val store =
      object : InventorySubmissionStore {
        override suspend fun load(ownerId: String, householdId: String) = null

        override suspend fun save(submission: InventorySubmission) = Unit
      }

  @Test
  fun eachFailedOperationRetriesAndOneRecoveryCannotHideAnotherFailure() = runTest {
    var recoveries = 0
    var receipts = 0
    var deletions = 0
    var allowReceipts = false
    var refreshed = 0
    var stopped = 0
    val disk =
        object : InventorySubmissionStore by store {
          override suspend fun resumeSync(ownerId: String, householdId: String) {
            if (++recoveries == 1) throw IOException("Offline")
          }
        }
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(ownerId: String, householdId: String) =
              flow<List<ReceiptCloudEntry>> {
                receipts++
                if (!allowReceipts) throw IOException("Offline")
                emit(emptyList())
                try {
                  awaitCancellation()
                } finally {
                  stopped++
                }
              }

          override fun observeDeleted(ownerId: String, householdId: String) =
              flow<Set<String>> {
                if (++deletions == 1) throw IOException("Offline")
                emit(emptySet())
                try {
                  awaitCancellation()
                } finally {
                  stopped++
                }
              }
        }
    val sync = ReceiptHistorySync(disk, cloud, "owner", "home")
    val job = sync.start(backgroundScope) { refreshed++ }
    runCurrent()
    assertTrue(sync.error.value)
    advanceTimeBy(RECEIPT_INITIAL_RETRY_MILLIS - 1)
    runCurrent()
    assertEquals(listOf(1, 1, 1), listOf(recoveries, receipts, deletions))
    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(2, 2, 2), listOf(recoveries, receipts, deletions))
    assertEquals(1, refreshed)
    assertTrue(sync.error.value)
    allowReceipts = true
    advanceTimeBy(RECEIPT_INITIAL_RETRY_MILLIS * 2)
    runCurrent()
    assertEquals(3, receipts)
    assertEquals(2, refreshed)
    assertFalse(sync.error.value)
    job.cancel()
    runCurrent()
    assertEquals(2, stopped)
    advanceTimeBy(RECEIPT_MAX_RETRY_MILLIS * 2)
    runCurrent()
    assertEquals(listOf(2, 3, 2), listOf(recoveries, receipts, deletions))
  }

  @Test
  fun repeatedFailureCapsBackoffAndCancellationStopsRetries() = runTest {
    var attempts = 0
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(
              ownerId: String,
              householdId: String
          ): Flow<List<ReceiptCloudEntry>> = flow {
            attempts++
            throw IOException("Unavailable")
          }
        }
    val sync = ReceiptHistorySync(store, cloud, "owner", "home")
    val job = sync.start(backgroundScope) {}
    runCurrent()
    for (delay in
        listOf(1L, 2L, 4L, 8L, 16L).map { it * RECEIPT_INITIAL_RETRY_MILLIS } +
            listOf(RECEIPT_MAX_RETRY_MILLIS, RECEIPT_MAX_RETRY_MILLIS)) {
      val before = attempts
      advanceTimeBy(delay - 1)
      runCurrent()
      assertEquals(before, attempts)
      advanceTimeBy(1)
      runCurrent()
      assertEquals(before + 1, attempts)
      assertTrue(sync.error.value)
    }
    job.cancel()
    runCurrent()
    val before = attempts
    advanceTimeBy(RECEIPT_MAX_RETRY_MILLIS * 2)
    runCurrent()
    assertEquals(before, attempts)
  }

  @Test
  fun cancellationIsNotReportedOrRetriedAsFailure() = runTest {
    var attempts = 0
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(
              ownerId: String,
              householdId: String
          ): Flow<List<ReceiptCloudEntry>> {
            attempts++
            throw CancellationException("Leaving")
          }

          override fun observeDeleted(ownerId: String, householdId: String) =
              emptyFlow<Set<String>>()
        }
    val sync = ReceiptHistorySync(store, cloud, "owner", "home")
    sync.start(backgroundScope) {}
    runCurrent()
    advanceTimeBy(RECEIPT_MAX_RETRY_MILLIS * 2)
    runCurrent()
    assertEquals(1, attempts)
    assertFalse(sync.error.value)
  }
}
