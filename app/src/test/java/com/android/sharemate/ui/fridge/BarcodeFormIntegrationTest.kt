package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeFormIntegrationTest {
  private val owner = ViewModelStore()
  private val items = FakeItemRepository()
  private val scanned = mutableListOf<String>()
  private val lookedUp = mutableListOf<String>()
  private var scan: suspend () -> BarcodeScanResult = { BarcodeScanResult.Found("0012345678905") }
  private var lookup: suspend () -> ProductLookupResult = { found("Milk", "Farm") }
  private lateinit var vm: FridgeViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    vm =
        FridgeViewModel(
            items,
            "user",
            "household",
            FoodImageSelector(
                object : FoodImageRepository {
                  override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
                }),
            BarcodeScanner { uri ->
              scanned += uri
              scan()
            },
            object : ProductCatalogRepository {
              override suspend fun lookupByBarcode(barcode: String): ProductLookupResult {
                lookedUp += barcode
                return lookup()
              }
            })
    owner.put("fridge", vm)
    vm.openAddItemDialog()
    vm.updateName("Manual food")
    vm.updateCategory("Dairy")
    vm.updateQuantity("3")
    vm.updateExpirationDate("2027-01-01")
  }

  @After
  fun tearDown() {
    owner.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun confirmedImageUsesScannerAndCatalogAndOnlyReplacesEditableName() = runTest {
    vm.confirmBarcodeImage("file:///private/photo.png")
    assertEquals(listOf("file:///private/photo.png"), scanned)
    assertEquals(listOf("0012345678905"), lookedUp)
    assertEquals("Milk — Farm", vm.uiState.value.itemName)
    assertManualFieldsPreserved()
    assertFalse(vm.uiState.value.isScanningBarcode)
    assertNull(vm.uiState.value.barcodeLookupError)
    assertTrue(items.addedItems.isEmpty())
    vm.updateName("Corrected milk")
    assertEquals("Corrected milk", vm.uiState.value.itemName)
    vm.updateName("Corrected milk — Farm")
    vm.saveItem()
    runCurrent()
    assertEquals("Corrected milk — Farm", items.addedItems.single().name)
    assertEquals(3, items.addedItems.single().quantity)
    assertEquals("Dairy", items.addedItems.single().category)
  }

  @Test
  fun partialCatalogDetailsAreTrimmedAndDoNotInventMissingFields() = runTest {
    for ((name, brand, expected) in
        listOf(
            Triple(" Milk ", null, "Milk"),
            Triple(null, " Farm ", "Farm"),
            Triple("Milk", "Milk", "Milk"))) {
      lookup = { found(name, brand) }
      vm.confirmBarcodeImage("file:///photo")
      assertEquals(expected, vm.uiState.value.itemName)
      assertManualFieldsPreserved()
    }
    lookup = { found(" ", null) }
    assertFailure(BarcodeLookupError.NO_DETAILS)
    assertEquals("Milk", vm.uiState.value.itemName)
  }

  @Test
  fun notFoundAndEveryCatalogFailurePreserveManualInputAndAllowRetry() = runTest {
    lookup = { ProductLookupResult.NotFound }
    assertFailure(BarcodeLookupError.NOT_FOUND)
    for (reason in ProductLookupResult.Reason.entries) {
      lookup = { ProductLookupResult.Failure(reason) }
      assertFailure(
          if (reason == ProductLookupResult.Reason.INVALID_BARCODE) BarcodeLookupError.UNREADABLE
          else BarcodeLookupError.LOOKUP_FAILED)
    }
    lookup = { throw IOException("catalog unavailable") }
    assertFailure(BarcodeLookupError.LOOKUP_FAILED)
    assertEquals("Manual food", vm.uiState.value.itemName)
    assertManualFieldsPreserved()
    lookup = { found("Recovered", "Brand") }
    vm.confirmBarcodeImage("file:///same-photo")
    assertEquals("Recovered — Brand", vm.uiState.value.itemName)
    assertNull(vm.uiState.value.barcodeLookupError)
  }

  @Test
  fun unreadableImageNeverCallsCatalogAndDoesNotEraseDraft() = runTest {
    scan = { BarcodeScanResult.NotFound }
    assertFailure(BarcodeLookupError.UNREADABLE)
    scan = { throw IOException("unreadable private file") }
    assertFailure(BarcodeLookupError.UNREADABLE)
    assertTrue(lookedUp.isEmpty())
    assertEquals("Manual food", vm.uiState.value.itemName)
  }

  @Test
  fun confirmationWaitsForLookupAndRejectsConcurrentSubmission() = runTest {
    val pending = CompletableDeferred<ProductLookupResult>()
    lookup = { pending.await() }
    val first = launch { vm.confirmBarcodeImage("file:///first") }
    runCurrent()
    assertTrue(vm.uiState.value.isScanningBarcode)
    assertFalse(first.isCompleted)
    try {
      vm.confirmBarcodeImage("file:///duplicate")
      fail("Concurrent confirmation must not start another lookup")
    } catch (_: CancellationException) {}
    assertEquals(listOf("file:///first"), scanned)
    vm.saveItem()
    runCurrent()
    assertTrue(items.addedItems.isEmpty())
    pending.complete(found("Milk", "Farm"))
    runCurrent()
    assertTrue(first.isCompleted)
    assertFalse(vm.uiState.value.isScanningBarcode)
    assertEquals("Milk — Farm", vm.uiState.value.itemName)
  }

  @Test
  fun manualEditDuringLookupWinsOverLateCatalogResponse() = runTest {
    val pending = CompletableDeferred<ProductLookupResult>()
    lookup = { pending.await() }
    launch { vm.confirmBarcodeImage("file:///photo") }
    runCurrent()
    vm.updateName("User correction")
    pending.complete(found("Catalog", "Brand"))
    runCurrent()
    assertEquals("User correction", vm.uiState.value.itemName)
    assertFalse(vm.uiState.value.isScanningBarcode)
    assertNull(vm.uiState.value.barcodeLookupError)
  }

  @Test
  fun closingAndReopeningIgnoresLateResultFromOldForm() = runTest {
    val pending = CompletableDeferred<ProductLookupResult>()
    lookup = { withContext(NonCancellable) { pending.await() } }
    val operation = launch { vm.confirmBarcodeImage("file:///old") }
    runCurrent()
    vm.dismissAddItemDialog()
    vm.openAddItemDialog()
    vm.updateName("New draft")
    pending.complete(found("Old result", "Old brand"))
    runCurrent()
    assertTrue(operation.isCancelled)
    assertEquals("New draft", vm.uiState.value.itemName)
    assertNull(vm.uiState.value.barcodeLookupError)
    assertFalse(vm.uiState.value.isScanningBarcode)
    lookup = { found("Fresh", null) }
    vm.confirmBarcodeImage("file:///new")
    assertEquals("Fresh", vm.uiState.value.itemName)
  }

  @Test
  fun cancellationClearsLoadingWithoutReportingLookupFailure() = runTest {
    lookup = { throw CancellationException("form disposed") }
    try {
      vm.confirmBarcodeImage("file:///photo")
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {}
    assertFalse(vm.uiState.value.isScanningBarcode)
    assertNull(vm.uiState.value.barcodeLookupError)
    assertEquals("Manual food", vm.uiState.value.itemName)
    vm.dismissAddItemDialog()
    try {
      vm.confirmBarcodeImage("file:///closed")
      fail("Closed form must reject lookup")
    } catch (_: CancellationException) {}
    assertEquals(1, scanned.size)
  }

  private suspend fun assertFailure(expected: BarcodeLookupError) {
    try {
      vm.confirmBarcodeImage("file:///same-photo")
      fail("Failure must leave photo confirmation retryable")
    } catch (_: BarcodeLookupException) {}
    assertEquals(expected, vm.uiState.value.barcodeLookupError)
    assertFalse(vm.uiState.value.isScanningBarcode)
  }

  private fun assertManualFieldsPreserved() {
    assertEquals("Dairy", vm.uiState.value.itemCategory)
    assertEquals("3", vm.uiState.value.itemQuantity)
    assertEquals("2027-01-01", vm.uiState.value.expirationDateInput)
    assertTrue(vm.uiState.value.isAddItemDialogOpen)
  }

  private fun found(name: String?, brand: String?) =
      ProductLookupResult.Found(ProductDetails("0012345678905", name, brand))
}
