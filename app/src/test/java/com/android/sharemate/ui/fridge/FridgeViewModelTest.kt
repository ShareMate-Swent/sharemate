// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.image.FoodImage
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FridgeViewModelTest {
  private val repository = FakeItemRepository()
  private val imageRepository = FakeFoodImageRepository()
  private val imageSelector = FoodImageSelector(imageRepository)
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

  private fun viewModel(householdId: String? = "test-household"): FridgeViewModel =
      FridgeViewModel(repository, "test-user", householdId, imageSelector).also {
        viewModelStore.put("fridge", it)
      }

  @Test
  fun realNonBlankContextIsRequired() {
    assertThrows(IllegalArgumentException::class.java) {
      FridgeViewModel(repository, " ", "test-household", imageSelector)
    }
    assertThrows(IllegalArgumentException::class.java) {
      FridgeViewModel(repository, "test-user", "", imageSelector)
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
  fun withoutHouseholdObservesOnlyCurrentUsersPrivateItems() = runTest {
    val privateItem = Item(id = "private", name = "Milk", ownerId = "test-user")
    repository.emitItems(
        listOf(
            privateItem,
            Item(id = "other-user", ownerId = "other-user"),
            Item(id = "shared", ownerId = "test-user", householdId = "test-household")))
    val viewModel = viewModel(householdId = null)
    runCurrent()

    assertEquals(listOf("test-user"), repository.requestedUserIds)
    assertTrue(repository.requestedHouseholdIds.isEmpty())
    assertEquals(listOf(privateItem), viewModel.uiState.value.items)
    assertFalse(viewModel.uiState.value.isLoading)
  }

  @Test
  fun withoutHouseholdSavesPrivateItemAndSearchesForImage() = runTest {
    val viewModel = viewModel(householdId = null)
    runCurrent()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    runCurrent()

    val saved = repository.addedItems.single()
    assertEquals("test-user", saved.ownerId)
    assertNull(saved.householdId)
    assertFalse(saved.isShared)
    assertEquals(listOf("Milk"), imageRepository.titles)
    assertEquals(listOf(saved.copy(id = "item-1")), viewModel.uiState.value.items)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    assertNull(viewModel.uiState.value.formError)
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

    assertTrue(imageRepository.titles.isEmpty())
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
    assertTrue(imageRepository.titles.isEmpty())
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

  @Test
  fun remoteImageAndAllCreditsArePassedToTheRepository() = runTest {
    imageRepository.result = FoodImageSearchResult.Found(remoteImage())
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("  Milk  ")
    viewModel.saveItem()
    runCurrent()

    val expected =
        ItemImage(
            origin = ItemImageOrigin.REMOTE_IMAGE,
            reference = "https://images.example.org/milk.jpg",
            author = "Jane Doe",
            sourceName = "Photo library",
            sourceUrl = "https://example.org/photos/42",
            title = "Fresh milk",
            license = "by",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            licenseVersion = "4.0",
            authorUrl = "https://example.org/jane")
    assertEquals(listOf("Milk"), imageRepository.titles)
    assertEquals(expected, repository.addedItems.single().image)
    assertEquals(expected, viewModel.uiState.value.items.single().image)
    assertFalse(viewModel.uiState.value.isSaving)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    assertNull(viewModel.uiState.value.formError)
  }

  @Test fun noImageResultStillSavesTheItemWithoutAnImage() = runTest { assertSavesWithoutImage() }

  @Test
  fun networkFailureResultStillSavesTheItemWithoutAnImage() = runTest {
    imageRepository.result = FoodImageSearchResult.Failure(FoodImageSearchResult.Reason.NETWORK)
    assertSavesWithoutImage()
  }

  @Test
  fun thrownLookupFailureStillSavesTheItemWithoutAnImage() = runTest {
    imageRepository.failure = IOException("Lookup failed")
    assertSavesWithoutImage()
  }

  private fun TestScope.assertSavesWithoutImage() {
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    runCurrent()

    assertEquals(listOf("Milk"), imageRepository.titles)
    assertNull(repository.addedItems.single().image)
    assertEquals("Milk", viewModel.uiState.value.items.single().name)
    assertFalse(viewModel.uiState.value.isSaving)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    assertNull(viewModel.uiState.value.formError)
  }

  @Test
  fun pendingLookupPreventsDuplicateSearchesAndSaves() = runTest {
    val searchGate = CompletableDeferred<Unit>()
    val saveGate = CompletableDeferred<Unit>()
    imageRepository.gate = searchGate
    imageRepository.result = FoodImageSearchResult.Found(remoteImage())
    repository.addGate = saveGate
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    viewModel.saveItem()
    assertTrue(viewModel.uiState.value.isSaving)
    runCurrent()
    viewModel.saveItem()
    viewModel.updateName("Bread")
    viewModel.dismissAddItemDialog()
    runCurrent()

    assertEquals(listOf("Milk"), imageRepository.titles)
    assertTrue(repository.addedItems.isEmpty())
    assertTrue(viewModel.uiState.value.isSaving)
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
    assertEquals("Milk", viewModel.uiState.value.itemName)

    searchGate.complete(Unit)
    runCurrent()
    assertEquals(1, repository.addedItems.size)
    assertTrue(viewModel.uiState.value.isSaving)
    viewModel.saveItem()
    saveGate.complete(Unit)
    runCurrent()

    assertEquals(listOf("Milk"), imageRepository.titles)
    assertEquals(1, repository.addedItems.size)
    assertEquals(1, viewModel.uiState.value.items.size)
    assertFalse(viewModel.uiState.value.isSaving)
  }

  @Test
  fun lookupCancellationIsPropagatedWithoutSavingOrReportingAnError() = runTest {
    val cancellation = CancellationException("Lookup cancelled")
    imageRepository.failure = cancellation
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    val saveJob = viewModel.viewModelScope.coroutineContext.job.children.last()
    var completionCause: Throwable? = null
    saveJob.invokeOnCompletion { completionCause = it }
    runCurrent()

    assertTrue(saveJob.isCancelled)
    assertSame(cancellation, completionCause)
    assertTrue(repository.addedItems.isEmpty())
    assertNull(viewModel.uiState.value.formError)
    assertFalse(viewModel.uiState.value.isSaving)
    assertTrue(viewModel.uiState.value.isAddItemDialogOpen)
    assertEquals("Milk", viewModel.uiState.value.itemName)
  }

  @Test
  fun clearingViewModelCancelsPendingLookupWithoutSaving() = runTest {
    val gate = CompletableDeferred<Unit>()
    imageRepository.gate = gate
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    runCurrent()
    assertEquals(listOf("Milk"), imageRepository.titles)
    assertTrue(viewModel.uiState.value.isSaving)

    viewModelStore.clear()
    runCurrent()
    gate.complete(Unit)
    runCurrent()

    assertTrue(repository.addedItems.isEmpty())
    assertFalse(viewModel.uiState.value.isSaving)
    assertNull(viewModel.uiState.value.formError)
  }

  @Test
  fun saveCancellationIsPropagatedWithoutReportingAnError() = runTest {
    val cancellation = CancellationException("Save cancelled")
    repository.addFailure = cancellation
    val viewModel = viewModel()
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    viewModel.saveItem()
    val saveJob = viewModel.viewModelScope.coroutineContext.job.children.last()
    var completionCause: Throwable? = null
    saveJob.invokeOnCompletion { completionCause = it }
    runCurrent()

    assertTrue(saveJob.isCancelled)
    assertSame(cancellation, completionCause)
    assertTrue(viewModel.uiState.value.items.isEmpty())
    assertNull(viewModel.uiState.value.formError)
    assertFalse(viewModel.uiState.value.isSaving)
    assertEquals("Milk", viewModel.uiState.value.itemName)
  }

  private fun remoteImage() =
      FoodImage(
          url = "https://images.example.org/milk.jpg",
          author = "Jane Doe",
          sourceName = "Photo library",
          sourceUrl = "https://example.org/photos/42",
          title = "Fresh milk",
          license = "by",
          licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
          licenseVersion = "4.0",
          authorUrl = "https://example.org/jane")

  private class FakeFoodImageRepository : FoodImageRepository {
    val titles = mutableListOf<String>()
    var result: FoodImageSearchResult = FoodImageSearchResult.NotFound
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun searchByTitle(title: String): FoodImageSearchResult {
      titles += title
      gate?.await()
      failure?.let { throw it }
      return result
    }
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
