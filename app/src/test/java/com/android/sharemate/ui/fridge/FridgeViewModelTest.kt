// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
