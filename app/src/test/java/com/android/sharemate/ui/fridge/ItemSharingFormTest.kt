// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.*
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ItemSharingFormTest {
  private val repository = FakeItemRepository()
  private val store = ViewModelStore()
  private var nextVm = 0
  private var barcodeResult: BarcodeScanResult = BarcodeScanResult.NotFound
  private val image =
      ItemImage(
          origin = ItemImageOrigin.REMOTE_IMAGE,
          reference = "https://example.com/milk.jpg",
          author = "Photographer",
          sourceName = "Commons",
          sourceUrl = "https://example.com/source",
          title = "Milk",
          license = "CC BY",
          licenseUrl = "https://example.com/license",
          licenseVersion = "4.0",
          authorUrl = "https://example.com/author")
  private val original = Item("milk", "Milk", "owner", "home", image = image)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun TestScope.vm(
      type: FridgeType? = FridgeType.SHARED,
      household: String? = "home",
      catalog: ProductCatalogRepository = testProductCatalogRepository(),
  ): FridgeViewModel {
    val images =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    return FridgeViewModel(
            repository,
            "owner",
            household,
            FoodImageSelector(images),
            BarcodeScanner { barcodeResult },
            catalog,
            fridgeType = type)
        .also {
          store.put("vm-${nextVm++}", it)
          runCurrent()
        }
  }

  @Test
  fun addSelectionIsDraftAndCancelRestoresSharedDefault() = runTest {
    val vm = vm()
    vm.openAddItemDialog()
    assertTrue(vm.uiState.value.showSharingSelector)
    assertTrue(vm.uiState.value.canChangeSharing)
    assertEquals(ItemSharing.SHARED, vm.uiState.value.itemSharing)
    vm.updateSharing(ItemSharing.PERSONAL)
    assertTrue(repository.addedItems.isEmpty())
    assertTrue(repository.edits.isEmpty())
    vm.dismissAddItemDialog()
    vm.openAddItemDialog()
    assertEquals(ItemSharing.SHARED, vm.uiState.value.itemSharing)
  }

  @Test
  fun creationPersistsBothChoicesWithAuthenticatedOwnerAndHousehold() = runTest {
    val vm = vm()
    for ((index, sharing) in ItemSharing.values().withIndex()) {
      vm.openAddItemDialog()
      vm.updateName(" Milk ")
      vm.updateSharing(sharing)
      vm.saveItem()
      vm.saveItem()
      runCurrent()
      assertEquals(index + 1, repository.addedItems.size)
      val saved = repository.addedItems.last()
      assertEquals(sharing, saved.sharing)
      assertEquals("owner", saved.ownerId)
      assertEquals("home", saved.householdId)
      assertFalse(vm.uiState.value.isAddItemDialogOpen)
    }
  }

  @Test
  fun editingPrefillsBothValuesAndCancelNeverWrites() = runTest {
    val vm = vm()
    for (sharing in ItemSharing.values()) {
      repository.emitItems(listOf(original.copy(sharing = sharing)))
      runCurrent()
      vm.openEditItemDialog(original.id)
      assertEquals(sharing, vm.uiState.value.itemSharing)
      assertTrue(vm.uiState.value.canChangeSharing)
      vm.updateSharing(
          if (sharing == ItemSharing.SHARED) ItemSharing.PERSONAL else ItemSharing.SHARED)
      vm.dismissAddItemDialog()
      assertTrue(repository.edits.isEmpty())
      vm.openEditItemDialog(original.id)
      assertEquals(sharing, vm.uiState.value.itemSharing)
      vm.dismissAddItemDialog()
    }
  }

  @Test
  fun editTransitionsAreOneSubmissionAndPreserveIdentityAndImage() = runTest {
    val vm = vm()
    for ((index, sharing) in ItemSharing.values().withIndex()) {
      val before = original.copy(sharing = sharing)
      repository.emitItems(listOf(before))
      runCurrent()
      vm.openEditItemDialog(before.id)
      val next = if (sharing == ItemSharing.SHARED) ItemSharing.PERSONAL else ItemSharing.SHARED
      vm.updateName("Oat milk")
      vm.updateQuantity("3")
      vm.updateSharing(next)
      assertEquals(index, repository.edits.size)
      vm.saveItem()
      vm.saveItem()
      runCurrent()
      assertEquals(index + 1, repository.edits.size)
      assertEquals(next, repository.edits.last().second.sharing)
      assertEquals(
          before.copy(name = "Oat milk", quantity = 3, sharing = next),
          vm.uiState.value.items.single())
    }
  }

  @Test
  fun nonOwnerCannotChangeSharingButCanStillEditOrdinaryFields() = runTest {
    val other = original.copy(ownerId = "another-member", sharing = ItemSharing.PERSONAL)
    repository.emitItems(listOf(other))
    val vm = vm()
    vm.openEditItemDialog(other.id)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    assertFalse(vm.uiState.value.canChangeSharing)
    vm.updateSharing(ItemSharing.SHARED)
    vm.updateName("Member edit")
    vm.saveItem()
    runCurrent()
    assertNull(repository.edits.single().second.sharing)
    assertEquals(other.copy(name = "Member edit"), vm.uiState.value.items.single())
  }

  @Test
  fun privateStandaloneAndUnknownTypesKeepExistingBehavior() = runTest {
    for ((type, household) in
        listOf(
            FridgeType.PRIVATE to "home",
            null to "home",
            null to null,
            FridgeType.SHARED to null)) {
      repository.emitItems(
          listOf(original.copy(householdId = household, sharing = ItemSharing.PERSONAL)))
      val vm = vm(type, household)
      assertFalse(vm.uiState.value.showSharingSelector)
      vm.openEditItemDialog(original.id)
      vm.updateSharing(ItemSharing.SHARED)
      vm.updateName("Private edit")
      vm.saveItem()
      runCurrent()
      assertNull(repository.edits.last().second.sharing)
      assertEquals(ItemSharing.PERSONAL, vm.uiState.value.items.single().sharing)
      vm.openAddItemDialog()
      vm.updateName("Bread")
      vm.updateSharing(ItemSharing.PERSONAL)
      vm.saveItem()
      runCurrent()
      assertEquals(
          if (household == null) ItemSharing.PERSONAL else ItemSharing.SHARED,
          repository.addedItems.last().sharing)
    }
  }

  @Test
  fun untouchedOrRevertedDraftNeverOverwritesNewerSharing() = runTest {
    val vm = vm()
    for (toggle in listOf(false, true)) {
      repository.emitItems(listOf(original))
      runCurrent()
      vm.openEditItemDialog(original.id)
      if (toggle) {
        vm.updateSharing(ItemSharing.PERSONAL)
        vm.updateSharing(ItemSharing.SHARED)
      }
      repository.emitItems(listOf(original.copy(sharing = ItemSharing.PERSONAL)))
      runCurrent()
      vm.updateName("Ordinary edit")
      vm.saveItem()
      runCurrent()
      assertNull(repository.edits.last().second.sharing)
      assertEquals(ItemSharing.PERSONAL, vm.uiState.value.items.single().sharing)
    }
  }

  @Test
  fun rejectedEditRetainsSharingDraftAndCanBeRetried() = runTest {
    repository.emitItems(listOf(original))
    val vm = vm()
    vm.openEditItemDialog(original.id)
    vm.updateSharing(ItemSharing.PERSONAL)
    repository.editFailure = IllegalStateException("Denied")
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    assertEquals(original, vm.uiState.value.items.single())
    repository.editFailure = null
    vm.saveItem()
    runCurrent()
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.items.single().sharing)
  }

  @Test
  fun failedCreationRetainsSelectionForRetry() = runTest {
    val vm = vm()
    vm.openAddItemDialog()
    vm.updateName("Milk")
    vm.updateSharing(ItemSharing.PERSONAL)
    repository.returnNullOnAdd = true
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    repository.returnNullOnAdd = false
    vm.saveItem()
    runCurrent()
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.items.single().sharing)
  }

  @Test
  fun offlineEditClosesOnSubmissionAndLateRejectionPreservesNewDraft() = runTest {
    repository.emitItems(listOf(original))
    repository.editConfirmation = CompletableDeferred()
    val vm = vm()
    vm.openEditItemDialog(original.id)
    vm.updateSharing(ItemSharing.PERSONAL)
    vm.saveItem()
    runCurrent()
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
    assertFalse(vm.uiState.value.isSaving)
    assertFalse(repository.editConfirmation!!.isCompleted)
    vm.openAddItemDialog()
    vm.updateSharing(ItemSharing.PERSONAL)
    repository.editConfirmation!!.complete(Result.failure(IllegalStateException("Denied")))
    runCurrent()
    assertNotNull(vm.uiState.value.editSyncError)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
  }

  @Test
  fun savingBlocksSelectionDismissalAndDuplicateSubmission() = runTest {
    val vm = vm()
    vm.updateSharing(ItemSharing.PERSONAL)
    assertEquals(ItemSharing.SHARED, vm.uiState.value.itemSharing)
    vm.openAddItemDialog()
    vm.updateName("Milk")
    vm.updateSharing(ItemSharing.PERSONAL)
    repository.addGate = CompletableDeferred()
    vm.saveItem()
    runCurrent()
    assertTrue(vm.uiState.value.isSaving)
    vm.updateSharing(ItemSharing.SHARED)
    vm.dismissAddItemDialog()
    vm.saveItem()
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
    assertEquals(1, repository.addedItems.size)
    repository.addGate!!.complete(Unit)
    runCurrent()
    assertFalse(vm.uiState.value.isAddItemDialogOpen)
  }

  @Test
  fun barcodeResultsPreserveTheSelectedSharingDraft() = runTest {
    barcodeResult = BarcodeScanResult.Found("123456789")
    val vm = vm()
    vm.openAddItemDialog()
    vm.updateSharing(ItemSharing.PERSONAL)
    vm.scanBarcodeImage("content://photos/barcode")
    runCurrent()
    assertEquals("123456789", vm.uiState.value.barcodeValue)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    vm.updateName("Milk")
    vm.saveItem()
    runCurrent()
    assertEquals(ItemSharing.PERSONAL, repository.addedItems.single().sharing)
  }

  @Test
  fun confirmedBarcodeLookupPreservesSharingThroughCreation() = runTest {
    barcodeResult = BarcodeScanResult.Found("0012345678905")
    val vm =
        vm(
            catalog =
                object : ProductCatalogRepository {
                  override suspend fun lookupByBarcode(barcode: String) =
                      ProductLookupResult.Found(ProductDetails(barcode, "Milk", "Farm"))
                })
    vm.openAddItemDialog()
    vm.updateSharing(ItemSharing.PERSONAL)
    vm.confirmBarcodeImage("content://photos/barcode")
    assertEquals("Milk — Farm", vm.uiState.value.itemName)
    assertEquals(ItemSharing.PERSONAL, vm.uiState.value.itemSharing)
    assertTrue(repository.addedItems.isEmpty())
    vm.saveItem()
    runCurrent()
    assertEquals(ItemSharing.PERSONAL, repository.addedItems.single().sharing)
  }

  @Test
  fun ownershipIsRecheckedBeforeAnAtomicSharingSave() = runTest {
    for (deliverSnapshotBeforeSave in listOf(false, true)) {
      repository.emitItems(listOf(original))
      val vm = vm()
      vm.openEditItemDialog(original.id)
      vm.updateSharing(ItemSharing.PERSONAL)
      repository.emitItems(listOf(original.copy(ownerId = "another-member")))
      if (deliverSnapshotBeforeSave) runCurrent()
      vm.saveItem()
      runCurrent()
      assertTrue(repository.edits.isEmpty())
      assertEquals(FridgeFormError.SAVE_FAILED, vm.uiState.value.formError)
    }
  }
}
