// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.*
import com.android.sharemate.model.staging.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiptCloudHistoryTest {
  @Test
  fun sharedSavedIsCachedForOfflineRestartAndOtherMembersDraftsStayHidden() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val lifecycle = ViewModelStore()
    try {
      val app = RuntimeEnvironment.getApplication()
      val store = AndroidInventorySubmissionStore(app, dispatcher).forViewer("bob")
      val cloud = Cloud()
      val history =
          ReceiptHistoryViewModel(
              mock(ItemRepository::class.java),
              store,
              mock(ReceiptPhotoStore::class.java),
              "bob",
              "home",
              cloud = cloud)
      lifecycle.put("first", history)
      runCurrent()
      val saved = entry("alice", false)
      cloud.entries.emit(listOf(saved, entry("alice", true)))
      runCurrent()
      assertEquals(listOf(saved.submission), history.receipts.value.map { it.submission })
      assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
      lifecycle.clear()
      val restarted =
          ReceiptHistoryViewModel(
              mock(ItemRepository::class.java),
              AndroidInventorySubmissionStore(app, dispatcher).forViewer("bob"),
              mock(ReceiptPhotoStore::class.java),
              "bob",
              "home")
      lifecycle.put("offline", restarted)
      runCurrent()
      assertEquals(saved.details, restarted.receipts.value.single().details)
    } finally {
      lifecycle.clear()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun transientEmptyCloudQueryNeverDeletesDraftButExplicitTombstoneDoes() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val lifecycle = ViewModelStore()
    try {
      val store = AndroidInventorySubmissionStore(RuntimeEnvironment.getApplication(), dispatcher)
      val cloud = Cloud()
      val history =
          ReceiptHistoryViewModel(
              mock(ItemRepository::class.java),
              store,
              mock(ReceiptPhotoStore::class.java),
              "alice",
              "home",
              cloud = cloud)
      lifecycle.put("history", history)
      runCurrent()
      val draft = entry("alice", true)
      cloud.entries.emit(listOf(draft))
      runCurrent()
      assertEquals(draft.submission, history.receipts.value.single().submission)
      cloud.entries.emit(emptyList())
      runCurrent()
      assertEquals(draft.submission, history.receipts.value.single().submission)
      cloud.deletions.emit(setOf(draft.submission.id))
      runCurrent()
      assertTrue(history.receipts.value.isEmpty())
    } finally {
      lifecycle.clear()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun ownerHistoryIgnoresOlderRevisionsAndAcceptsNewerOnes() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val lifecycle = ViewModelStore()
    try {
      val store = AndroidInventorySubmissionStore(RuntimeEnvironment.getApplication(), dispatcher)
      val cloud = Cloud()
      val saved = entry("alice", false)
      store.save(saved.submission)
      store.saveDetails(saved.details.copy(title = "Latest", revision = 2))
      val history =
          ReceiptHistoryViewModel(
              mock(ItemRepository::class.java),
              store,
              mock(ReceiptPhotoStore::class.java),
              "alice",
              "home",
              cloud = cloud)
      lifecycle.put("history", history)
      runCurrent()
      cloud.entries.emit(listOf(saved))
      runCurrent()
      assertEquals("Latest", history.receipts.value.single().details?.title)
      assertEquals(2L, history.receipts.value.single().details?.revision)
      cloud.entries.emit(
          listOf(saved.copy(details = saved.details.copy(title = "Latest", revision = 3))))
      runCurrent()
      assertEquals(3L, history.receipts.value.single().details?.revision)
    } finally {
      lifecycle.clear()
      Dispatchers.resetMain()
    }
  }

  private fun entry(owner: String, draft: Boolean): ReceiptCloudEntry {
    val id = UUID.randomUUID().toString()
    val expense = SharedExpense(id, "home", owner, 100, mapOf(owner to 100L))
    val submission =
        InventorySubmission(
            id,
            owner,
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100)),
            draft,
            if (draft) null else expense,
            !draft,
            if (draft) ExpenseProgress.QUEUED else ExpenseProgress.CONFIRMED)
    return ReceiptCloudEntry(
        submission, ReceiptDetails(id, owner, "home", "photo", 1, title = "Courses"))
  }

  private class Cloud : ReceiptCloudRepository {
    val entries = MutableSharedFlow<List<ReceiptCloudEntry>>()
    val deletions = MutableSharedFlow<Set<String>>()

    override fun observe(ownerId: String, householdId: String) = entries

    override fun observeDeleted(ownerId: String, householdId: String) = deletions
  }
}
