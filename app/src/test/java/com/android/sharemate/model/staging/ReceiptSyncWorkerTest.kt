// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.android.sharemate.model.item.InventorySubmission
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptSyncWorkerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val id = UUID.randomUUID().toString()
  private val input = workDataOf("owner" to "owner", "home" to "home", "id" to id)

  private fun worker(
      data: Data = input,
      user: String? = "owner",
      sync: suspend (String, String, String, String, Boolean) -> Boolean = { _, _, _, _, _ -> true }
  ) =
      TestListenableWorkerBuilder<ReceiptSyncWorker>(context)
          .setInputData(data)
          .setWorkerFactory(
              object : WorkerFactory() {
                override fun createWorker(c: Context, name: String, params: WorkerParameters) =
                    ReceiptSyncWorker(c, params, { user }, sync)
              })
          .build()

  @Test
  fun evictionDiscardsQueuedDeletesEvenAfterRejoin() = runTest {
    val lifecycle = com.android.sharemate.model.receipt.ReceiptCacheLifecycle(context)
    lifecycle.evict("owner", "home")
    lifecycle.activate("home")
    val deletion = Data.Builder().putAll(input).putBoolean("delete", true).build()
    assertEquals(
        ListenableWorker.Result.success(),
        worker(deletion) { _, _, _, _, _ ->
              error("An old deletion job must never run after leaving")
            }
            .doWork())
  }

  @Test
  fun validatesInputAndWaitsForTheSameSignedInActor() = runTest {
    for (missing in listOf("owner", "home", "id")) {
      val data = Data.Builder().putAll(input.keyValueMap.filterKeys { it != missing }).build()
      assertEquals(ListenableWorker.Result.failure(), worker(data).doWork())
    }
    for (user in listOf(null, "someone-else")) {
      assertEquals(ListenableWorker.Result.retry(), worker(user = user).doWork())
    }
    val peer =
        Data.Builder().putAll(input).putString("actor", "peer").putBoolean("delete", true).build()
    assertEquals(
        ListenableWorker.Result.success(),
        worker(peer, "peer") { owner, home, receipt, actor, delete ->
              assertEquals(listOf("owner", "home", id, "peer"), listOf(owner, home, receipt, actor))
              assertTrue(delete)
              true
            }
            .doWork())
  }

  @Test
  fun retriesPendingNetworkFailuresAndTimeoutButPropagatesCancellation() = runTest {
    assertEquals(ListenableWorker.Result.retry(), worker { _, _, _, _, _ -> false }.doWork())
    assertEquals(
        ListenableWorker.Result.retry(),
        worker { _, _, _, _, _ -> throw IllegalStateException("offline") }.doWork())
    assertEquals(
        ListenableWorker.Result.retry(),
        worker { _, _, _, _, _ ->
              delay(60_001)
              true
            }
            .doWork())
    val cancellation = CancellationException("Stopped")
    val failure =
        runCatching { worker { _, _, _, _, _ -> throw cancellation }.doWork() }.exceptionOrNull()
    assertTrue(failure is CancellationException)
    assertEquals(cancellation.message, failure?.message)
  }

  @Test
  fun defaultWorkerBacksUpTheDurableDraftAndEnqueuesNetworkConstrainedWork() = runBlocking {
    val auth = mock(FirebaseAuth::class.java)
    val user = mock(FirebaseUser::class.java)
    `when`(auth.currentUser).thenReturn(user)
    `when`(user.uid).thenReturn("owner")
    val db = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(
          context,
          FirebaseOptions.Builder()
              .setApplicationId("1:123:android:receipt-tests")
              .setApiKey("emulator-only-key")
              .setProjectId("demo-sharemate-ci")
              .build())
    }
    val storage = mock(FirebaseStorage::class.java)
    val manager = mock(WorkManager::class.java)
    mockStatic(FirebaseAuth::class.java).use { a ->
      mockStatic(FirebaseFirestore::class.java).use { d ->
        mockStatic(FirebaseStorage::class.java).use { s ->
          mockStatic(WorkManager::class.java).use { w ->
            a.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            d.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(db)
            s.`when`<FirebaseStorage> { FirebaseStorage.getInstance() }.thenReturn(storage)
            w.`when`<WorkManager> { WorkManager.getInstance(context) }.thenReturn(manager)
            `when`(db.runTransaction<Any?>(any())).thenReturn(Tasks.forResult(null))
            val receipt =
                InventorySubmission(
                    id, "owner", "home", listOf(StagedItem("row", "Milk")), draft = true)
            val store = AndroidInventorySubmissionStore(context, scheduleSync = false)
            store.save(receipt)
            store.saveDetails(ReceiptDetails(id, "owner", "home", "", 1))
            assertEquals(
                ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<ReceiptSyncWorker>(context)
                    .setInputData(input)
                    .build()
                    .doWork())
            verify(db).runTransaction<Any?>(any())
            ReceiptSyncWorker.enqueue(context, receipt)
            `when`(auth.currentUser).thenReturn(null)
            ReceiptSyncWorker.enqueue(context, receipt, delete = true, replace = false)
            val requests = org.mockito.ArgumentCaptor.forClass(OneTimeWorkRequest::class.java)
            verify(manager)
                .enqueueUniqueWork(
                    eq("receipt-sync-owner-$id"),
                    eq(ExistingWorkPolicy.REPLACE),
                    requests.capture())
            verify(manager)
                .enqueueUniqueWork(
                    eq("receipt-sync-owner-$id"), eq(ExistingWorkPolicy.KEEP), requests.capture())
            assertTrue(requests.value.workSpec.input.getBoolean("delete", false))
            assertEquals("owner", requests.value.workSpec.input.getString("actor"))
            assertEquals(
                NetworkType.CONNECTED, requests.value.workSpec.constraints.requiredNetworkType)
          }
        }
      }
    }
  }
}
