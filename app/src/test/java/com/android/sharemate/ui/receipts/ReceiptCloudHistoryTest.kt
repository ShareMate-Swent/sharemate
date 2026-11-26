// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
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
  fun sharedSavedIsCachedForOfflineRestartAndOtherMembersDraftsStayHidden() = cloudTest {
    val disk = diskStore()
    val store = disk.forViewer("bob")
    val cloud = Cloud()
    val history = model(store, "bob", cloud)
    val saved = entry("alice", false)
    cloud.entries.emit(listOf(saved, entry("alice", true)))
    runCurrent()
    assertEquals(listOf(saved.submission), history.receipts.value.map { it.submission })
    assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
    lifecycle.clear()
    val restarted = model(diskStore().forViewer("bob"), "bob", EmptyReceiptCloud)
    assertEquals(saved.details, restarted.receipts.value.single().details)
    assertTrue(disk.list("alice", "home").isEmpty())
  }

  @Test
  fun transientEmptyCloudQueryNeverDeletesDraftButExplicitTombstoneDoes() = cloudTest {
    val store = diskStore()
    val cloud = Cloud()
    val history = model(store, "alice", cloud)
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
  }

  @Test
  fun ownerHistoryIgnoresOlderRevisionsAndAcceptsNewerOnes() = cloudTest {
    val store = diskStore()
    val cloud = Cloud()
    val saved = entry("alice", false)
    store.save(saved.submission)
    store.saveDetails(saved.details.copy(title = "Latest", revision = 2))
    val history = model(store, "alice", cloud)
    cloud.entries.emit(listOf(saved))
    runCurrent()
    assertEquals("Latest", history.receipts.value.single().details?.title)
    assertEquals(2L, history.receipts.value.single().details?.revision)
    cloud.entries.emit(
        listOf(saved.copy(details = saved.details.copy(title = "Latest", revision = 3))))
    runCurrent()
    assertEquals(3L, history.receipts.value.single().details?.revision)
  }

  private val lifecycle = ViewModelStore()

  private fun cloudTest(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      block()
    } finally {
      lifecycle.clear()
      Dispatchers.resetMain()
    }
  }

  private fun TestScope.diskStore() =
      AndroidInventorySubmissionStore(
          RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))

  private fun TestScope.model(
      store: InventorySubmissionStore,
      owner: String,
      cloud: ReceiptCloudRepository
  ) =
      ReceiptHistoryViewModel(
              mock(ItemRepository::class.java),
              store,
              mock(ReceiptPhotoStore::class.java),
              owner,
              "home",
              mock(ExpenseSubmissionRepository::class.java),
              ReceiptHistorySync(store, cloud, owner, "home"),
              { 0L })
          .also {
            lifecycle.put(UUID.randomUUID().toString(), it)
            runCurrent()
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
