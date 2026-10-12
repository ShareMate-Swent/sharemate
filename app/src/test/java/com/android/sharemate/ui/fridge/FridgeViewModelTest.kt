// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FridgeViewModelTest {
  private val repository = FakeItemRepository()
  private val viewModelStore = ViewModelStore()

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    Dispatchers.resetMain()
  }

  private val milk = Item(id = "milk", name = "Milk", householdId = "test-household")
  private val bread = Item(id = "bread", name = "Bread", householdId = "test-household")

  @Test
  fun activeViewExcludesHistoryAndPreservesSortingAndCombinedFilters() = runTest {
    val vm = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.visibleItems.collect() }
    val active = milk.copy(ownerId = "other-member", category = "Dairy")
    val history =
        listOf(
            active.copy(id = "eaten", status = ItemStatus.EATEN),
            active.copy(id = "discarded", status = ItemStatus.DISCARDED))
    repository.emitItems(history + bread.copy(category = "Bakery") + active)
    vm.setSortOrder(FridgeSortOrder.NAME)
    runCurrent()
    assertEquals(listOf(bread.id, milk.id), vm.visibleItems.value.map { it.id })
    vm.setCategoryFilter(" dairy ")
    vm.setOwnerFilter("other-member")
    runCurrent()
    assertEquals(listOf(active), vm.visibleItems.value)
    assertEquals(4, vm.uiState.value.items.size)
  }

  @Test
  fun offlineStatusActionsPreserveDocumentsAndReleaseOnlyLocalLocks() = runTest {
    repository.emitItems(listOf(milk.copy(ownerId = "other-member"), bread))
    repository.statusConfirmation = CompletableDeferred()
    val vm = viewModel()
    runCurrent()
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    vm.setItemStatus(milk.id, ItemStatus.DISCARDED)
    assertEquals(setOf(milk.id), vm.uiState.value.pendingStatusIds)
    vm.openEditItemDialog(milk.id)
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    runCurrent()
    assertTrue(vm.uiState.value.pendingStatusIds.isEmpty())
    assertFalse(repository.statusConfirmation!!.isCompleted)
    vm.setItemStatus(bread.id, ItemStatus.DISCARDED)
    runCurrent()
    assertEquals(
        listOf(milk.id to ItemStatus.EATEN, bread.id to ItemStatus.DISCARDED),
        repository.statusChanges)
    assertEquals(
        listOf(
            milk.copy(ownerId = "other-member", status = ItemStatus.EATEN),
            bread.copy(status = ItemStatus.DISCARDED)),
        vm.uiState.value.items)
    assertTrue(repository.deletedItemIds.isEmpty())
    assertTrue(repository.edits.isEmpty())
    vm.openAddItemDialog()
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun statusFailuresAndCancellationPreserveItemsAndAllowRetry() = runTest {
    for (failure in listOf(IOException("Failed"), CancellationException("Cancelled"))) {
      for (throws in listOf(false, true)) {
        repository.emitItems(listOf(milk))
        repository.statusFailure = if (throws) null else failure
        repository.statusException = if (throws) failure else null
        val vm = viewModel()
        runCurrent()
        vm.setItemStatus(milk.id, ItemStatus.EATEN)
        runCurrent()
        assertEquals(listOf(milk), vm.uiState.value.items)
        assertTrue(vm.uiState.value.pendingStatusIds.isEmpty())
        assertEquals(failure !is CancellationException, vm.uiState.value.statusError != null)
        vm.uiState.value.statusError?.let {
          assertFalse(it.isSyncFailure)
          vm.dismissStatusError(it)
        }
        repository.statusFailure = null
        repository.statusException = null
        vm.setItemStatus(milk.id, ItemStatus.EATEN)
        runCurrent()
        assertEquals(ItemStatus.EATEN, vm.uiState.value.items.single().status)
      }
    }
  }

  @Test
  fun olderStatusRejectionPreservesNewerStateDraftAndErrorIdentity() = runTest {
    repository.emitItems(listOf(milk))
    val first = CompletableDeferred<Result<Unit>>()
    val second = CompletableDeferred<Result<Unit>>()
    val vm = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.visibleItems.collect() }
    runCurrent()
    repository.statusConfirmation = first
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    runCurrent()
    assertTrue(vm.visibleItems.value.isEmpty())
    repository.emitItems(listOf(milk)) // Firestore rejection rollback restores the active card.
    runCurrent()
    assertEquals(listOf(milk), vm.visibleItems.value)
    repository.statusConfirmation = second
    vm.setItemStatus(milk.id, ItemStatus.DISCARDED)
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    runCurrent()
    second.complete(Result.failure(IOException("Newer rejection")))
    runCurrent()
    val error = vm.uiState.value.statusError!!
    assertTrue(error.isSyncFailure)
    assertEquals(ItemStatus.DISCARDED, vm.uiState.value.items.single().status)
    repository.emitItems(listOf(milk.copy(name = "Newer values")))
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.updateName("Unsaved draft")
    first.completeExceptionally(IOException("Older rejection"))
    runCurrent()
    assertEquals(2, repository.statusChanges.size)
    assertEquals("Newer values", vm.uiState.value.items.single().name)
    assertEquals("Unsaved draft", vm.uiState.value.itemName)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    vm.dismissStatusError(error)
    assertTrue(vm.uiState.value.statusError !== error)
    assertEquals(milk.name, vm.uiState.value.statusError?.itemName)
  }

  @Test
  fun statusTargetsOnlyActiveHouseholdItemsAndRespectsDialogs() = runTest {
    repository.emitItems(
        listOf(
            milk,
            bread.copy(status = ItemStatus.EATEN),
            milk.copy(id = "private", ownerId = "test-user", householdId = null),
            milk.copy(id = "foreign", householdId = "other-household")))
    val vm = viewModel()
    runCurrent()
    listOf("", "missing", bread.id, "private", "foreign").forEach {
      vm.setItemStatus(it, ItemStatus.DISCARDED)
    }
    vm.setItemStatus(milk.id, ItemStatus.ACTIVE)
    vm.openEditItemDialog(milk.id)
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    vm.dismissAddItemDialog()
    vm.requestRemoval(milk.id)
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    vm.cancelRemoval()
    repository.emitItems(emptyList())
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    runCurrent()
    assertTrue(repository.statusChanges.isEmpty())
    assertTrue(repository.deletedItemIds.isEmpty())
  }

  @Test
  fun clearingViewModelDetachesStatusConfirmationWithoutCancellingWrite() = runTest {
    repository.emitItems(listOf(milk))
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.statusConfirmation = confirmation
    val vm = viewModel()
    runCurrent()
    vm.setItemStatus(milk.id, ItemStatus.EATEN)
    runCurrent()
    viewModelStore.clear()
    assertFalse(confirmation.isCancelled)
    confirmation.complete(Result.failure(IOException("Late rejection")))
    runCurrent()
    assertNull(vm.uiState.value.statusError)
    assertTrue(vm.uiState.value.pendingStatusIds.isEmpty())
  }

  @Test
  fun editPrefillsAllFieldsAndCancelDiscardsChanges() = runTest {
    val original = milk.copy(quantity = 3, category = "Dairy", expirationDate = Date(0))
    repository.emitItems(listOf(original))
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    assertEquals(milk.id, vm.uiState.value.editingItemId)
    assertEquals("Milk", vm.uiState.value.itemName)
    assertEquals("3", vm.uiState.value.itemQuantity)
    assertEquals("Dairy", vm.uiState.value.itemCategory)
    assertEquals("1970-01-01", vm.uiState.value.expirationDateInput)
    vm.updateName("Changed")
    vm.updateQuantity("5")
    vm.dismissAddItemDialog()
    assertTrue(repository.edits.isEmpty())
    assertNull(vm.uiState.value.editingItemId)
    vm.openEditItemDialog(milk.id)
    assertEquals("Milk", vm.uiState.value.itemName)
    assertEquals("3", vm.uiState.value.itemQuantity)
  }

  @Test
  fun editUpdatesExistingItemAndPreservesLatestMetadata() = runTest {
    repository.emitItems(listOf(milk.copy(ownerId = "original"), bread))
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    val latest = milk.copy(ownerId = "latest")
    repository.emitItems(listOf(latest, bread))
    runCurrent()
    vm.updateName(" Cheese ")
    vm.updateQuantity(" 4 ")
    vm.updateCategory(" Dairy ")
    vm.updateExpirationDate("2026-12-31")
    vm.saveItem()
    vm.saveItem()
    vm.updateName("Ignored")
    vm.dismissAddItemDialog()
    runCurrent()
    val expected =
        latest.copy(
            name = "Cheese",
            quantity = 4,
            category = "Dairy",
            expirationDate =
                Date.from(LocalDate.parse("2026-12-31").atStartOfDay(ZoneOffset.UTC).toInstant()))
    assertEquals(listOf(expected, bread), vm.uiState.value.items)
    assertEquals(1, repository.edits.size)
    assertEquals(milk.id, repository.edits.single().first)
    assertTrue(repository.addedItems.isEmpty())
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun invalidEditInputsNeverSubmitAndOptionalFieldsCanBeCleared() = runTest {
    repository.emitItems(listOf(milk.copy(category = "Dairy", expirationDate = Date(0))))
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.updateName(" ")
    vm.saveItem()
    assertEquals(FridgeFormError.NAME_REQUIRED, vm.uiState.value.formError)
    vm.updateName("Milk")
    vm.updateExpirationDate("2026-02-30")
    vm.saveItem()
    assertEquals(FridgeFormError.INVALID_EXPIRATION_DATE, vm.uiState.value.formError)
    vm.updateExpirationDate("")
    listOf("", "0", "-1", "1.5", "abc", "2147483648").forEach {
      vm.updateQuantity(it)
      vm.saveItem()
      assertEquals(FridgeFormError.INVALID_QUANTITY, vm.uiState.value.formError)
    }
    assertTrue(repository.edits.isEmpty())
    vm.updateQuantity("2147483647")
    vm.updateCategory(" ")
    vm.saveItem()
    runCurrent()
    assertEquals(milk.copy(quantity = Int.MAX_VALUE), vm.uiState.value.items.single())
  }

  @Test
  fun rejectedEditRetainsDraftAndAllowsRetry() = runTest {
    repository.emitItems(listOf(milk))
    repository.editFailure = IOException("Rejected")
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.updateName("Cheese")
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    assertEquals("Cheese", vm.uiState.value.itemName)
    assertEquals(listOf(milk), vm.uiState.value.items)
    assertFalse(vm.uiState.value.isSaving)
    repository.editFailure = null
    repository.editException = IOException("Submission failed")
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    assertEquals("Cheese", vm.uiState.value.itemName)
    assertFalse(vm.uiState.value.isSaving)
    repository.editException = null
    vm.saveItem()
    runCurrent()
    assertEquals("Cheese", vm.uiState.value.items.single().name)
  }

  @Test
  fun queuedEditAppliesOfflineWithoutLoadingAndServerRollbackIsReactive() = runTest {
    repository.emitItems(listOf(milk))
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = confirmation
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.updateName("Cheese")
    vm.saveItem()
    runCurrent()
    assertEquals("Cheese", vm.uiState.value.items.single().name)
    assertFalse(vm.uiState.value.isSaving)
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    vm.openEditItemDialog(milk.id)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    vm.updateName("New draft")
    val second = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = second
    vm.saveItem()
    vm.saveItem()
    confirmation.complete(Result.success(Unit))
    assertTrue(vm.uiState.value.isSaving)
    runCurrent()
    assertEquals(2, repository.edits.size)
    assertEquals("New draft", vm.uiState.value.items.single().name)
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    vm.openEditItemDialog(milk.id)
    vm.updateName("Unsaved draft")
    repository.emitItems(listOf(milk))
    second.complete(Result.failure(IOException("Permission denied")))
    runCurrent()
    assertEquals(listOf(milk), vm.uiState.value.items)
    assertEquals("New draft", vm.uiState.value.editSyncError?.itemName)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    assertEquals("Unsaved draft", vm.uiState.value.itemName)
    vm.dismissEditSyncError(vm.uiState.value.editSyncError!!)
    assertNull(vm.uiState.value.editSyncError)
  }

  @Test
  fun olderRejectionNeverOverwritesNewerEditOrClearsNewerError() = runTest {
    repository.emitItems(listOf(milk))
    val first = CompletableDeferred<Result<Unit>>()
    val second = CompletableDeferred<Result<Unit>>()
    val vm = viewModel()
    runCurrent()
    listOf(first, second).forEachIndexed { index, confirmation ->
      repository.editConfirmation = confirmation
      vm.openEditItemDialog(milk.id)
      vm.updateName("Edit $index")
      vm.saveItem()
      runCurrent()
    }
    second.complete(Result.failure(IOException("Newer rejection")))
    runCurrent()
    val error = vm.uiState.value.editSyncError!!
    first.completeExceptionally(IOException("Older rejection"))
    runCurrent()
    assertEquals("Edit 1", vm.uiState.value.items.single().name)
    assertEquals("Edit 0", vm.uiState.value.editSyncError?.itemName)
    vm.dismissEditSyncError(error)
    assertEquals("Edit 0", vm.uiState.value.editSyncError?.itemName)
  }

  @Test
  fun cancelledEditPropagatesWithoutReportingSaveFailure() = runTest {
    repository.emitItems(listOf(milk))
    repository.editFailure = CancellationException("Cancelled")
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.saveItem()
    runCurrent()
    assertFalse(vm.uiState.value.isSaving)
    assertNull(vm.uiState.value.formError)
    assertEquals(listOf(milk), vm.uiState.value.items)
  }

  @Test
  fun editTargetsOnlyCurrentActiveHouseholdItemsAndNeverInterruptsAnotherDialog() = runTest {
    repository.emitItems(
        listOf(
            milk,
            bread.copy(status = ItemStatus.EATEN),
            milk.copy(id = "private", householdId = null)))
    val vm = viewModel()
    runCurrent()
    listOf("", "unknown", bread.id, "private").forEach(vm::openEditItemDialog)
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    vm.requestRemoval(milk.id)
    vm.openEditItemDialog(milk.id)
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    vm.cancelRemoval()
    vm.openEditItemDialog(milk.id)
    vm.requestRemoval(milk.id)
    assertNull(vm.uiState.value.pendingRemovalItem)
    listOf(listOf(bread), listOf(milk.copy(status = ItemStatus.EATEN))).forEach { changed ->
      repository.emitItems(changed)
      runCurrent()
      vm.saveItem()
      assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
      assertTrue(repository.edits.isEmpty())
    }
    repository.emitItems(emptyList())
    runCurrent()
    vm.saveItem()
    assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    assertTrue(repository.edits.isEmpty())
  }

  @Test
  fun editedFieldsImmediatelyReapplyExistingFiltersAndSorting() = runTest {
    repository.emitItems(listOf(milk.copy(category = "Dairy"), bread.copy(category = "Dairy")))
    val vm = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.visibleItems.collect() }
    vm.setCategoryFilter("dairy")
    vm.setSortOrder(FridgeSortOrder.NAME)
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.updateName("Apple")
    vm.saveItem()
    runCurrent()
    assertEquals(listOf("Apple", "Bread"), vm.visibleItems.value.map { it.name })
    vm.openEditItemDialog(milk.id)
    vm.updateCategory("Fruit")
    vm.saveItem()
    runCurrent()
    assertEquals(listOf(bread.copy(category = "Dairy")), vm.visibleItems.value)
  }

  @Test
  fun clearingViewModelCancelsConfirmationWaitWithoutCancellingQueuedWrite() = runTest {
    repository.emitItems(listOf(milk))
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = confirmation
    val vm = viewModel()
    runCurrent()
    vm.openEditItemDialog(milk.id)
    vm.saveItem()
    runCurrent()
    viewModelStore.clear()
    runCurrent()
    assertNull(vm.uiState.value.editSyncError)
    assertFalse(confirmation.isCancelled)
    assertTrue(confirmation.complete(Result.failure(IOException("Rejected after clear"))))
    runCurrent()
    assertNull(vm.uiState.value.editSyncError)
  }

  @Test
  fun offlineRemovalReappliesFiltersAndSortingWithoutAwaitingConfirmation() = runTest {
    val apple = milk.copy(id = "apple", name = "Apple", category = "Dairy")
    val cheese = milk.copy(id = "cheese", name = "Cheese", category = "Dairy")
    repository.emitItems(listOf(cheese, apple, bread))
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.deleteConfirmation = confirmation
    val vm = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.visibleItems.collect() }
    vm.setCategoryFilter("Dairy")
    vm.setSortOrder(FridgeSortOrder.NAME)
    runCurrent()
    vm.requestRemoval(apple.id)
    repository.emitItems(listOf(bread, apple, cheese))
    runCurrent()
    vm.confirmRemoval()
    vm.confirmRemoval()
    runCurrent()
    assertEquals(listOf(apple.id), repository.queuedDeletionIds)
    assertEquals(listOf(cheese), vm.visibleItems.value)
    assertEquals(listOf(bread, cheese), vm.uiState.value.items)
    assertNull(vm.uiState.value.pendingRemovalItem)
    assertFalse(vm.uiState.value.isDeleting)
    assertFalse(confirmation.isCompleted)
    vm.requestRemoval(cheese.id)
    assertEquals(cheese, vm.uiState.value.pendingRemovalItem)
  }

  @Test
  fun removalRejectionRollbackPreservesNewerDialogDraftAndErrorIdentity() = runTest {
    repository.emitItems(listOf(milk, bread))
    val first = CompletableDeferred<Result<Unit>>()
    val second = CompletableDeferred<Result<Unit>>()
    val vm = viewModel()
    runCurrent()
    repository.deleteConfirmation = first
    vm.requestRemoval(milk.id)
    vm.confirmRemoval()
    runCurrent()
    repository.deleteConfirmation = second
    vm.requestRemoval(bread.id)
    vm.confirmRemoval()
    runCurrent()
    second.complete(Result.failure(IOException("Newer rejection")))
    repository.emitItems(listOf(bread))
    runCurrent()
    val newerError = vm.uiState.value.removalSyncError!!
    assertEquals("Bread", newerError.itemName)
    vm.requestRemoval(bread.id)
    first.completeExceptionally(IOException("Older rejection"))
    repository.emitItems(listOf(milk, bread))
    runCurrent()
    assertEquals(bread, vm.uiState.value.pendingRemovalItem)
    assertEquals(listOf(milk, bread), vm.uiState.value.items)
    vm.dismissRemovalSyncError(newerError)
    assertEquals("Milk", vm.uiState.value.removalSyncError?.itemName)
    vm.cancelRemoval()
    vm.openEditItemDialog(bread.id)
    vm.updateName("Unsaved draft")
    vm.dismissRemovalSyncError(vm.uiState.value.removalSyncError!!)
    assertEquals("Unsaved draft", vm.uiState.value.itemName)
    assertNull(vm.uiState.value.removalSyncError)
  }

  @Test
  fun cancelledAndDetachedRemovalConfirmationsNeverReportFailure() = runTest {
    for (clear in listOf(false, true)) {
      repository.emitItems(listOf(milk))
      val confirmation = CompletableDeferred<Result<Unit>>()
      repository.deleteConfirmation = confirmation
      val vm = viewModel()
      runCurrent()
      vm.requestRemoval(milk.id)
      vm.confirmRemoval()
      runCurrent()
      if (clear) {
        viewModelStore.clear()
        assertFalse(confirmation.isCancelled)
        confirmation.complete(Result.failure(IOException("Rejected after clear")))
      } else confirmation.cancel()
      runCurrent()
      assertNull(vm.uiState.value.removalSyncError)
      assertFalse(vm.uiState.value.removalFailed)
      assertFalse(vm.uiState.value.isDeleting)
      assertTrue(vm.uiState.value.items.isEmpty())
    }
  }

  @Test
  fun cancelledRemovalResetsPendingWithoutReportingFailure() = runTest {
    repository.emitItems(listOf(milk))
    repository.deleteFailure = CancellationException("Deletion cancelled")
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    assertTrue(viewModel.uiState.value.isDeleting)
    runCurrent()

    assertFalse(viewModel.uiState.value.isDeleting)
    assertFalse(viewModel.uiState.value.removalFailed)
    assertEquals(listOf(milk), viewModel.uiState.value.items)
    assertEquals(milk, viewModel.uiState.value.pendingRemovalItem)
    assertEquals(listOf(milk.id), repository.deletedItemIds)
  }

  @Test
  fun requestingAndCancellingRemovalDoesNotDelete() = runTest {
    repository.emitItems(listOf(milk))
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)

    assertEquals(milk, viewModel.uiState.value.pendingRemovalItem)
    assertTrue(repository.deletedItemIds.isEmpty())
    viewModel.cancelRemoval()
    viewModel.confirmRemoval()
    runCurrent()
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    assertEquals(listOf(milk), viewModel.uiState.value.items)
    assertTrue(repository.deletedItemIds.isEmpty())
  }

  @Test
  fun successfulRemovalUsesQueuedDeletionAndWaitsForLocalSnapshot() = runTest {
    repository.emitItems(listOf(milk, bread))
    repository.emitOnDelete = false
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    runCurrent()

    assertEquals(listOf(milk.id), repository.deletedItemIds)
    assertEquals(listOf(milk.id), repository.queuedDeletionIds)
    assertEquals(listOf(milk, bread), viewModel.uiState.value.items)
    repository.emitItems(listOf(bread))
    runCurrent()
    assertEquals(listOf(bread), viewModel.uiState.value.items)
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    assertFalse(viewModel.uiState.value.isDeleting)
    assertFalse(viewModel.uiState.value.removalFailed)
  }

  @Test
  fun removingTheLastItemLeavesAnEmptyInventory() = runTest {
    repository.emitItems(listOf(milk))
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    runCurrent()

    assertTrue(viewModel.uiState.value.items.isEmpty())
    assertFalse(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.pendingRemovalItem)
  }

  @Test
  fun failedRemovalRetainsTheItemAndAllowsRetry() = runTest {
    repository.emitItems(listOf(milk))
    repository.returnFalseOnDelete = true
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    runCurrent()

    assertEquals(listOf(milk), viewModel.uiState.value.items)
    assertEquals(milk, viewModel.uiState.value.pendingRemovalItem)
    assertTrue(viewModel.uiState.value.removalFailed)
    assertFalse(viewModel.uiState.value.isDeleting)
    repository.returnFalseOnDelete = false
    viewModel.confirmRemoval()
    runCurrent()
    assertEquals(listOf(milk.id, milk.id), repository.deletedItemIds)
    assertTrue(viewModel.uiState.value.items.isEmpty())
    assertFalse(viewModel.uiState.value.removalFailed)
  }

  @Test
  fun removalExceptionRetainsTheItemAndAllowsRetry() = runTest {
    repository.emitItems(listOf(milk))
    repository.deleteException = IOException("Delete failed")
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    runCurrent()

    assertEquals(listOf(milk), viewModel.uiState.value.items)
    assertTrue(viewModel.uiState.value.removalFailed)
    assertEquals(milk, viewModel.uiState.value.pendingRemovalItem)
    assertFalse(viewModel.uiState.value.isDeleting)
    assertNull(viewModel.uiState.value.formError)
    assertFalse(viewModel.uiState.value.isSaving)
    repository.deleteException = null
    viewModel.confirmRemoval()
    runCurrent()
    assertTrue(viewModel.uiState.value.items.isEmpty())
  }

  @Test
  fun pendingRemovalPreventsDuplicateRequestsAndCancellation() = runTest {
    repository.emitItems(listOf(milk, bread))
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    viewModel.confirmRemoval()
    viewModel.cancelRemoval()
    viewModel.requestRemoval(bread.id)
    assertTrue(viewModel.uiState.value.isDeleting)
    assertEquals(milk, viewModel.uiState.value.pendingRemovalItem)
    assertEquals(listOf(milk, bread), viewModel.uiState.value.items)
    runCurrent()
    assertEquals(listOf(milk.id), repository.deletedItemIds)
    assertEquals(listOf(bread), viewModel.uiState.value.items)
    assertFalse(viewModel.uiState.value.isDeleting)
  }

  @Test
  fun unknownAndBlankIdsNeverDelete() = runTest {
    val inactive = bread.copy(status = ItemStatus.EATEN)
    repository.emitItems(listOf(milk, inactive, milk.copy(id = "foreign", householdId = "other")))
    val viewModel = viewModel()
    runCurrent()
    listOf("unknown", "", " ", inactive.id, "foreign").forEach { id ->
      viewModel.requestRemoval(id)
      viewModel.confirmRemoval()
    }
    runCurrent()
    assertTrue(repository.deletedItemIds.isEmpty())
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    assertEquals(listOf(milk, inactive), viewModel.uiState.value.items)
  }

  @Test
  fun anItemRemovedFromTheInventoryBeforeConfirmationNeverDeletes() = runTest {
    repository.emitItems(listOf(milk))
    val viewModel = viewModel()
    runCurrent()
    viewModel.requestRemoval(milk.id)
    repository.emitItems(emptyList())
    runCurrent()
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    viewModel.confirmRemoval()
    runCurrent()

    assertTrue(repository.deletedItemIds.isEmpty())
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    assertFalse(viewModel.uiState.value.isDeleting)
  }

  @Test
  fun removalDoesNotInterruptAnOpenAddItemDraft() = runTest {
    repository.emitItems(listOf(milk))
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Cheese")
    viewModel.requestRemoval(milk.id)
    viewModel.confirmRemoval()
    runCurrent()

    assertTrue(repository.deletedItemIds.isEmpty())
    assertNull(viewModel.uiState.value.pendingRemovalItem)
    assertEquals("Cheese", viewModel.uiState.value.itemName)
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
  }

  private fun viewModel(): FridgeViewModel =
      FridgeViewModel(repository, "test-user", "test-household").also {
        viewModelStore.put("fridge", it)
      }

  @Test
  fun realNonBlankContextIsRequired() {
    assertThrows(IllegalArgumentException::class.java) {
      FridgeViewModel(repository, " ", "test-household")
    }
    assertThrows(IllegalArgumentException::class.java) {
      FridgeViewModel(repository, "test-user", "")
    }
    assertTrue(repository.requestedHouseholdIds.isEmpty())
    assertTrue(repository.requestedUserIds.isEmpty())
  }

  @Test
  fun observesOnlyTheRequestedSharedHousehold() = runTest {
    val shared = Item(id = "shared", name = "Milk", householdId = "test-household")
    repository.emitItems(
        listOf(shared, Item(id = "other", householdId = "other-household"), Item(id = "private")))
    val viewModel = viewModel()
    runCurrent()

    assertEquals(listOf("test-household"), repository.requestedHouseholdIds)
    assertTrue(repository.requestedUserIds.isEmpty())
    assertEquals(listOf(shared), viewModel.uiState.value.items)
    assertFalse(viewModel.uiState.value.isLoading)
  }

  @Test
  fun savingUsesExplicitContextAndTrimsOptionalValues() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("  Milk  ")
    viewModel.updateCategory("  Dairy  ")
    viewModel.updateExpirationDate("  2026-10-12  ")
    viewModel.saveItem()
    runCurrent()

    val expected =
        Item(
            name = "Milk",
            ownerId = "test-user",
            householdId = "test-household",
            expirationDate =
                Date.from(LocalDate.of(2026, 10, 12).atStartOfDay(ZoneOffset.UTC).toInstant()),
            category = "Dairy")
    assertEquals(listOf(expected), repository.addedItems)
    assertEquals(listOf(expected.copy(id = "item-1")), viewModel.uiState.value.items)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    assertFalse(viewModel.uiState.value.isSaving)
    assertEquals("", viewModel.uiState.value.itemName)
    assertNull(viewModel.uiState.value.formError)
  }

  @Test
  fun omittedCategoryAndExpirationAreNull() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Bread")
    viewModel.updateCategory(" ")
    viewModel.updateExpirationDate(" ")
    viewModel.saveItem()
    runCurrent()

    assertNull(repository.addedItems.single().category)
    assertNull(repository.addedItems.single().expirationDate)
    assertEquals("Bread", viewModel.uiState.value.items.single().name)
  }

  @Test
  fun blankNameDoesNotWrite() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName(" \t ")
    viewModel.saveItem()
    runCurrent()

    assertEquals(FridgeFormError.NAME_REQUIRED, viewModel.uiState.value.formError)
    assertTrue(repository.addedItems.isEmpty())
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun invalidDateFormatsAndImpossibleDatesDoNotWrite() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    listOf("2026-02-30", "2026-13-01", "2026-2-01", "12/10/2026", "not a date").forEach { date ->
      viewModel.updateExpirationDate(date)
      viewModel.saveItem()
      runCurrent()
      assertEquals(FridgeFormError.INVALID_EXPIRATION_DATE, viewModel.uiState.value.formError)
    }
    assertTrue(repository.addedItems.isEmpty())
  }

  @Test
  fun validPastLeapDateIsAcceptedWithoutExpirationLogic() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.updateExpirationDate("2024-02-29")
    viewModel.saveItem()
    runCurrent()

    assertEquals(
        Date.from(LocalDate.of(2024, 2, 29).atStartOfDay(ZoneOffset.UTC).toInstant()),
        repository.addedItems.single().expirationDate)
  }

  @Test
  fun cancelClearsTheDraftWithoutWriting() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.updateCategory("Dairy")
    viewModel.updateExpirationDate("2026-10-12")
    viewModel.dismissAddItemDialog()

    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    assertEquals("", viewModel.uiState.value.itemName)
    assertEquals("", viewModel.uiState.value.itemCategory)
    assertEquals("", viewModel.uiState.value.expirationDateInput)
    assertTrue(repository.addedItems.isEmpty())
  }

  @Test
  fun failedSaveKeepsTheDraftAndCanBeRetried() = runTest {
    repository.returnNullOnAdd = true
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    runCurrent()

    assertEquals(FridgeFormError.SAVE_FAILED, viewModel.uiState.value.formError)
    assertTrue(viewModel.uiState.value.items.isEmpty())
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
    assertEquals("Milk", viewModel.uiState.value.itemName)
    assertFalse(viewModel.uiState.value.isSaving)

    repository.returnNullOnAdd = false
    viewModel.saveItem()
    runCurrent()
    assertEquals(1, viewModel.uiState.value.items.size)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun thrownWriteFailureIsReportedWithoutAddingAnItem() = runTest {
    repository.addFailure = IOException("Write failed")
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    runCurrent()

    assertEquals(FridgeFormError.SAVE_FAILED, viewModel.uiState.value.formError)
    assertTrue(viewModel.uiState.value.items.isEmpty())
    assertEquals("Milk", viewModel.uiState.value.itemName)
    assertFalse(viewModel.uiState.value.isSaving)
  }

  @Test
  fun pendingSavePreventsDuplicateWritesAndDraftChanges() = runTest {
    val gate = CompletableDeferred<Unit>()
    repository.addGate = gate
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    viewModel.saveItem()
    viewModel.updateName("Bread")
    viewModel.dismissAddItemDialog()
    runCurrent()

    assertEquals(1, repository.addedItems.size)
    assertTrue(viewModel.uiState.value.isSaving)
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
    assertEquals("Milk", viewModel.uiState.value.itemName)

    gate.complete(Unit)
    runCurrent()
    assertEquals(1, viewModel.uiState.value.items.size)
    assertFalse(viewModel.uiState.value.isSaving)
  }

  @Test
  fun inventoryUpdatesPreserveAnOpenDraft() = runTest {
    val viewModel = viewModel()
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    val incoming = Item(id = "incoming", name = "Bread", householdId = "test-household")
    repository.emitItems(listOf(incoming))
    runCurrent()

    assertEquals(listOf(incoming), viewModel.uiState.value.items)
    assertEquals("Milk", viewModel.uiState.value.itemName)
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun readFailuresAreReported() = runTest {
    repository.readFailure = IOException("Read failed")
    val viewModel = viewModel()
    runCurrent()

    assertTrue(viewModel.uiState.value.loadFailed)
    assertFalse(viewModel.uiState.value.isLoading)
  }

  @Test
  fun sortsSharedItemsByExpirationWithUndatedItemsLast() = runTest {
    val viewModel = viewModel()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    val later = item("later", expirationDate = Date(2_000))
    val undated = item("undated")
    val sooner = item("sooner", expirationDate = Date(1_000))
    repository.emitItems(listOf(later, undated, sooner))
    runCurrent()

    assertEquals(listOf(sooner, later, undated), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun sortsItemsByNameWithoutCaseSensitivity() = runTest {
    val viewModel = viewModel()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    val banana = item("banana", name = "banana")
    val apple = item("apple", name = "Apple")
    val carrot = item("carrot", name = "carrot")
    repository.emitItems(listOf(banana, carrot, apple))
    viewModel.setSortOrder(FridgeSortOrder.NAME)
    runCurrent()

    assertEquals(listOf(apple, banana, carrot), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun categoryFilterIgnoresCaseAndWhitespaceAndCanBeCleared() = runTest {
    val viewModel = viewModel()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    val milk = item("milk", category = " Dairy ")
    val apple = item("apple", category = "Fruit")
    repository.emitItems(listOf(milk, apple))
    viewModel.setCategoryFilter(" dairy ")
    runCurrent()

    assertEquals(listOf(milk), viewModel.visibleItems.value)

    viewModel.setCategoryFilter("")
    runCurrent()
    assertNull(viewModel.selectedCategory.value)
    assertEquals(listOf(milk, apple), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun combinesOwnerAndCategoryFiltersAndReactsToInventoryChanges() = runTest {
    val viewModel = viewModel()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    val matching = item("matching", category = "Dairy", ownerId = "owner-1")
    val otherOwner = item("other-owner", category = "Dairy", ownerId = "owner-2")
    val otherCategory = item("other-category", category = "Fruit", ownerId = "owner-1")
    repository.emitItems(listOf(matching, otherOwner, otherCategory))
    viewModel.setCategoryFilter("dairy")
    viewModel.setOwnerFilter("owner-1")
    runCurrent()

    assertEquals(listOf(matching), viewModel.visibleItems.value)

    val anotherMatch = item("another-match", category = "Dairy", ownerId = "owner-1")
    repository.emitItems(listOf(matching, otherOwner, otherCategory, anotherMatch))
    runCurrent()
    assertEquals(listOf(matching, anotherMatch), viewModel.visibleItems.value)
    collection.cancel()
  }
}

private fun item(
    id: String,
    name: String = id,
    expirationDate: Date? = null,
    category: String? = null,
    ownerId: String = "test-user",
) =
    Item(
        id = id,
        name = name,
        ownerId = ownerId,
        householdId = "test-household",
        expirationDate = expirationDate,
        category = category,
    )
