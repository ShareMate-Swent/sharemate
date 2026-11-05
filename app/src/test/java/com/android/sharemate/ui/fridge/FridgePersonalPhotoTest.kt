// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.*
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.product.ProductCatalogRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class FridgePersonalPhotoTest {
  @get:Rule val temporary = TemporaryFolder()
  private val items = FakeItemRepository()
  private val models = ViewModelStore()
  private val reference =
      PersonalPhotoReference("itemPhotos/item-1/00000000-0000-0000-0000-000000000001")
  private lateinit var selected: File
  private lateinit var viewModel: FridgeViewModel
  private val controller
    get() = checkNotNull(viewModel.personalPhotos)

  private var copyPhoto: suspend (String) -> File = { uri ->
    if (uri == "invalid") throw IOException("Unreadable")
    selected
  }
  private var automaticLookups = 0
  private var uploaded = 0
  private val deleted = mutableListOf<PersonalPhotoReference>()
  private var upload: suspend () -> Result<PersonalPhotoReference> = { Result.success(reference) }
  private var deleteAttached: suspend () -> Result<Unit> = { Result.success(Unit) }
  private val storage =
      object : PersonalPhotoRepository {
        override suspend fun upload(itemId: String, source: File): Result<PersonalPhotoReference> {
          assertEquals("item-1", itemId)
          assertTrue(source.exists())
          uploaded++
          return upload()
        }

        override suspend fun load(reference: PersonalPhotoReference) =
            Result.success(byteArrayOf(1))

        override suspend fun delete(reference: PersonalPhotoReference): Result<Unit> {
          deleted += reference
          return Result.success(Unit)
        }

        override suspend fun deleteAttached(itemId: String) = deleteAttached()
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    selected = temporary.newFile()
    val files =
        object : PersonalPhotoFiles {
          override suspend fun copy(uri: String) = copyPhoto(uri)

          override suspend fun delete(file: File) {
            file.delete()
          }
        }
    val selector =
        FoodImageSelector(
            object : FoodImageRepository {
              override suspend fun searchByTitle(title: String): FoodImageSearchResult {
                automaticLookups++
                return FoodImageSearchResult.NotFound
              }
            })
    viewModel =
        FridgeViewModel(
            items,
            "owner",
            "home",
            selector,
            BarcodeScanner { BarcodeScanResult.NotFound },
            mock(ProductCatalogRepository::class.java),
            storage,
            files)
    models.put("fridge", viewModel)
  }

  @After
  fun tearDown() {
    models.clear()
    Dispatchers.resetMain()
  }

  private fun TestScope.selectPhoto() {
    viewModel.openAddItemDialog()
    viewModel.updateName("Milk")
    controller.select("content://gallery/selected")
    runCurrent()
  }

  @Test
  fun savesBeforeUploadAndShowsTheAttachedReferenceFromInventory() = runTest {
    val save = CompletableDeferred<Unit>()
    val transfer = CompletableDeferred<Unit>()
    items.addGate = save
    upload = {
      transfer.await()
      Result.success(reference)
    }
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    assertEquals(0, uploaded)
    save.complete(Unit)
    runCurrent()
    assertEquals(1, uploaded)
    assertFalse(viewModel.uiState.value.isAddItemDialogOpen)
    val saved = viewModel.uiState.value.items.single()
    assertNull(saved.image)
    transfer.complete(Unit)
    runCurrent()
    assertEquals(
        saved.copy(image = ItemImage(reference = reference.path)),
        viewModel.uiState.value.items.single())
    assertEquals(1, items.addedItems.size)
    assertEquals(0, automaticLookups)
    assertFalse(selected.exists())
  }

  @Test
  fun invalidReplacementAndFailedCreationPreserveTheDraft() = runTest {
    selectPhoto()
    controller.select(null)
    controller.select("invalid")
    runCurrent()
    assertTrue(controller.draft.value.rejected)
    assertEquals(selected, controller.draft.value.file)
    items.returnNullOnAdd = true
    viewModel.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.SAVE_FAILED, viewModel.uiState.value.formError)
    assertEquals(selected, controller.draft.value.file)
    assertEquals(0, uploaded)
    viewModel.dismissAddItemDialog()
    runCurrent()
    assertFalse(selected.exists())
  }

  @Test
  fun failedUploadLeavesTheItemAndClearsTheMessageAfterTenSeconds() = runTest {
    upload = { Result.failure(IOException("Offline")) }
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    assertEquals("Milk", controller.failure.value)
    assertNull(viewModel.uiState.value.items.single().image)
    assertFalse(selected.exists())
    advanceTimeBy(9_999)
    runCurrent()
    assertEquals("Milk", controller.failure.value)
    advanceTimeBy(1)
    runCurrent()
    assertNull(controller.failure.value)
    assertEquals(1, items.addedItems.size)
  }

  @Test
  fun staleUploadDoesNotOverwriteAnImageAndDeletesItsUnusedBlob() = runTest {
    val transfer = CompletableDeferred<Unit>()
    upload = {
      transfer.await()
      Result.success(reference)
    }
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    val updated =
        viewModel.uiState.value.items.single().copy(image = ItemImage(reference = "other"))
    items.emitItems(listOf(updated))
    transfer.complete(Unit)
    runCurrent()
    assertEquals(updated, viewModel.uiState.value.items.single())
    assertEquals(listOf(reference), deleted)
  }

  @Test
  fun uncertainAttachmentDoesNotDeleteAPotentiallyReferencedBlob() = runTest {
    items.imageUpdateFailure = IOException("Confirmation lost")
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    assertEquals("Milk", controller.failure.value)
    assertTrue(deleted.isEmpty())
    assertFalse(selected.exists())
  }

  @Test
  fun sessionEndCancelsUploadAndReleasesThePrivateCopy() = runTest {
    upload = { CompletableDeferred<Result<PersonalPhotoReference>>().await() }
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    assertTrue(selected.exists())
    models.clear()
    runCurrent()
    assertFalse(selected.exists())
    assertTrue(items.imageUpdates.isEmpty())
  }

  @Test
  fun removingAnItemWaitsForItsPhotoAndKeepsTheItemOnStorageFailure() = runTest {
    selectPhoto()
    viewModel.saveItem()
    runCurrent()
    val removal = CompletableDeferred<Result<Unit>>()
    deleteAttached = { removal.await() }
    viewModel.requestRemoval("item-1")
    viewModel.confirmRemoval()
    runCurrent()
    assertTrue(items.deletedItemIds.isEmpty())
    removal.complete(Result.failure(IOException("Offline")))
    runCurrent()
    assertTrue(viewModel.uiState.value.removalFailed)
    assertTrue(items.deletedItemIds.isEmpty())
    deleteAttached = { Result.success(Unit) }
    viewModel.confirmRemoval()
    runCurrent()
    assertEquals(listOf("item-1"), items.deletedItemIds)
  }

  @Test
  fun replacementBlocksSaveAndExtraSelectionsUntilTheCopyIsReady() = runTest {
    selectPhoto()
    val replacement = temporary.newFile()
    val pendingCopy = CompletableDeferred<File>()
    val requests = mutableListOf<String>()
    copyPhoto = { uri ->
      requests += uri
      pendingCopy.await()
    }

    controller.select("content://gallery/replacement")
    runCurrent()
    controller.select("content://gallery/extra")
    viewModel.saveItem()
    runCurrent()

    assertEquals(listOf("content://gallery/replacement"), requests)
    assertEquals(selected, controller.draft.value.file)
    assertTrue(selected.exists())
    assertTrue(items.addedItems.isEmpty())

    pendingCopy.complete(replacement)
    runCurrent()
    assertEquals(replacement, controller.draft.value.file)
    assertFalse(selected.exists())
    viewModel.saveItem()
    runCurrent()
    assertEquals(reference.path, viewModel.uiState.value.items.single().image?.reference)
    assertFalse(replacement.exists())
  }

  @Test
  fun dismissingDuringReplacementCancelsTheImportAndReleasesThePreviousDraft() = runTest {
    selectPhoto()
    val cancelled = CompletableDeferred<Unit>()
    copyPhoto = {
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    }
    controller.select("content://gallery/replacement")
    runCurrent()

    viewModel.dismissAddItemDialog()
    runCurrent()

    assertTrue(cancelled.isCompleted)
    assertEquals(PersonalPhotoDraft(), controller.draft.value)
    assertFalse(selected.exists())
    assertEquals(0, uploaded)
  }

  @Test
  fun removalWaitsForInFlightUploadAndTimesOutIfStorageNeverConfirmsDeletion() = runTest {
    val transfer = CompletableDeferred<Unit>()
    upload = {
      transfer.await()
      Result.success(reference)
    }
    var storageDeletionStarted = false
    deleteAttached = {
      storageDeletionStarted = true
      awaitCancellation()
    }
    selectPhoto()
    viewModel.saveItem()
    runCurrent()

    viewModel.requestRemoval("item-1")
    viewModel.confirmRemoval()
    runCurrent()
    assertFalse(storageDeletionStarted)
    assertTrue(items.queuedDeletionIds.isEmpty())

    transfer.complete(Unit)
    runCurrent()
    assertTrue(storageDeletionStarted)
    assertEquals(reference.path, viewModel.uiState.value.items.single().image?.reference)
    assertTrue(items.queuedDeletionIds.isEmpty())

    advanceTimeBy(20_000)
    runCurrent()
    assertTrue(viewModel.uiState.value.removalFailed)
    assertTrue(items.queuedDeletionIds.isEmpty())
    assertEquals("item-1", viewModel.uiState.value.items.single().id)
  }
}
