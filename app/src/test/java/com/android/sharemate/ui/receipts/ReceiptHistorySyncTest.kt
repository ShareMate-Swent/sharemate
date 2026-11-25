// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudPermanentException
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.staging.InventorySubmissionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
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
    advanceTimeBy(300_000)
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
    advanceTimeBy(999)
    runCurrent()
    assertEquals(listOf(1, 1, 1), listOf(recoveries, receipts, deletions))
    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(2, 2, 2), listOf(recoveries, receipts, deletions))
    assertEquals(1, refreshed)
    assertTrue(sync.error.value)
    allowReceipts = true
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(3, receipts)
    assertEquals(2, refreshed)
    assertFalse(sync.error.value)
    job.cancel()
    runCurrent()
    assertEquals(2, stopped)
    advanceTimeBy(60_000)
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
    for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
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
    advanceTimeBy(60_000)
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
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(1, attempts)
    assertFalse(sync.error.value)
  }
}
