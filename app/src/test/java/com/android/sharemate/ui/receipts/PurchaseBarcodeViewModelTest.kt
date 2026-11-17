// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.StagingViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseBarcodeViewModelTest {
  @Test
  fun restoredCompletedScanCannotReplayAndClearedOwnerRejectsLateLookup() = runTest {
    val saved = SavedStateHandle()
    val first =
        PurchaseBarcodeViewModel(BarcodeScanner { BarcodeScanResult.Found("123") }, catalog, saved)
    first.startScan()
    assertNotNull(first.read("image"))
    val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    val waiting = CompletableDeferred<BarcodeScanResult>()
    var closed = false
    val scanner =
        object : BarcodeScanner {
          override suspend fun scan(imageUri: String) = waiting.await()

          override fun close() {
            closed = true
          }
        }
    val recreated = PurchaseBarcodeViewModel(scanner, catalog, restored)
    assertNull(recreated.read("replayed image"))
    recreated.startScan()
    val late = async { recreated.read("new image") }
    runCurrent()
    ViewModelStore().apply {
      put("barcode", recreated)
      clear()
    }
    assertTrue(closed)
    waiting.complete(BarcodeScanResult.Found("123"))
    assertNull(late.await())
    assertNull(recreated.error.value)
    assertEquals(2, calls)
  }

  @Test
  fun timeoutAndUnexpectedFailuresRemainRetryable() = runTest {
    var hang = true
    val vm =
        model(BarcodeScanner { if (hang) awaitCancellation() else BarcodeScanResult.Found("123") })
    vm.startScan()
    assertTrue(runCatching { vm.read("timeout") }.isFailure)
    assertEquals(PurchaseBarcodeError.LOOKUP_FAILED, vm.error.value)
    hang = false
    assertNotNull(vm.read("retry"))
    val broken = model(BarcodeScanner { throw IllegalArgumentException("decode failed") })
    broken.startScan()
    assertTrue(runCatching { broken.read("broken") }.isFailure)
    assertEquals(PurchaseBarcodeError.LOOKUP_FAILED, broken.error.value)
  }

  @Test
  fun cancelledCoroutineDoesNotPublishAndBusyCallbackIsIgnored() = runTest {
    val waiting = CompletableDeferred<BarcodeScanResult>()
    val vm = model(BarcodeScanner { waiting.await() })
    assertNull(vm.read("no session"))
    vm.startScan()
    val pending = async { vm.read("first") }
    runCurrent()
    assertNull(vm.read("replay while busy"))
    pending.cancel()
    assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    assertNull(vm.error.value)
    waiting.complete(BarcodeScanResult.Found("123"))
    assertNotNull(vm.read("retry"))
  }

  @Test
  fun oldFailureCannotOverwriteNewSessionAndBrandFallbackIsUnpriced() = runTest {
    val waiting = CompletableDeferred<BarcodeScanResult>()
    val vm = model(BarcodeScanner { waiting.await() })
    vm.startScan()
    val old = async { vm.read("old") }
    runCurrent()
    vm.startScan()
    waiting.complete(BarcodeScanResult.NotFound)
    assertNull(old.await())
    assertNull(vm.error.value)
    result = ProductLookupResult.Found(ProductDetails("123", null, "  Brand  "))
    val fallback = model()
    fallback.startScan()
    assertEquals("Brand", checkNotNull(fallback.read("brand")).name)
    assertNull(fallback.read("brand"))
  }

  private var result: ProductLookupResult =
      ProductLookupResult.Found(ProductDetails("123", "Milk", null))
  private var calls = 0
  private val catalog =
      object : ProductCatalogRepository {
        override suspend fun lookupByBarcode(barcode: String): ProductLookupResult {
          calls++
          assertEquals("123", barcode)
          return result
        }
      }

  private fun model(scanner: BarcodeScanner = BarcodeScanner { BarcodeScanResult.Found("123") }) =
      PurchaseBarcodeViewModel(scanner, catalog, SavedStateHandle())

  @Test
  fun replayIsIgnoredButIntentionalRescanAddsSeparateUnpricedRow() = runTest {
    val vm = model()
    vm.startScan()
    val first = checkNotNull(vm.read("image"))
    assertEquals("Milk", first.name)
    assertEquals(1, first.quantity)
    assertNull(first.priceCents)
    assertNull(vm.read("image"))
    assertEquals(1, calls)
    vm.startScan()
    val second = checkNotNull(vm.read("image"))
    assertNotEquals(first.id, second.id)
    assertEquals(2, calls)
    val staging =
        StagingViewModel(emptyList(), PurchaseReview.forUser("alice", mapOf("alice" to "Alice")))
    staging.appendBarcode(first)
    staging.startEditing(first.id)
    staging.updateDraftName("Corrected milk")
    staging.updateDraftPrice("10.00")
    staging.appendBarcode(second)
    assertEquals("Corrected milk", staging.state.value.draft?.name)
    assertEquals(1, staging.state.value.items.size)
    staging.saveDraft()
    staging.appendBarcode(first)
    staging.appendBarcode(second)
    assertEquals(listOf("Corrected milk", "Milk"), staging.state.value.items.map { it.name })
    assertEquals(1000L, staging.state.value.items.first().priceCents)
    assertNull(staging.confirmedPurchase())
  }

  @Test
  fun staleLookupCannotPublishAfterCancellationOrNewSession() = runTest {
    val waiting = CompletableDeferred<BarcodeScanResult>()
    val vm = model(BarcodeScanner { waiting.await() })
    vm.startScan()
    val old = async { vm.read("old") }
    runCurrent()
    vm.cancelScan()
    vm.startScan()
    waiting.complete(BarcodeScanResult.Found("123"))
    assertNull(old.await())
    assertNotNull(vm.read("new"))
    assertNull(vm.error.value)
  }

  @Test
  fun unreadableNotFoundAndFailedLookupsLeaveExistingRowsUntouched() = runTest {
    val unreadable = model(BarcodeScanner { BarcodeScanResult.NotFound })
    unreadable.startScan()
    assertTrue(runCatching { unreadable.read("image") }.isFailure)
    assertEquals(PurchaseBarcodeError.UNREADABLE, unreadable.error.value)
    val vm = model()
    for ((outcome, error) in
        listOf(
            ProductLookupResult.NotFound to PurchaseBarcodeError.NOT_FOUND,
            ProductLookupResult.Failure(ProductLookupResult.Reason.NETWORK) to
                PurchaseBarcodeError.LOOKUP_FAILED,
            ProductLookupResult.Found(ProductDetails("123", "  ", null)) to
                PurchaseBarcodeError.NO_DETAILS)) {
      result = outcome
      vm.startScan()
      assertTrue(runCatching { vm.read("image") }.isFailure)
      assertEquals(error, vm.error.value)
    }
    vm.cancelScan()
    assertNull(vm.error.value)
  }

  @Test
  fun emptyPurchaseDraftIsRetainedButCannotBeSubmitted() {
    val id = UUID.randomUUID().toString()
    assertTrue(InventorySubmission(id, "alice", "home", emptyList(), draft = true).items.isEmpty())
    assertThrows(IllegalArgumentException::class.java) {
      InventorySubmission(id, "alice", "home", emptyList())
    }
    assertNull(StagingViewModel(emptyList()).confirmedItems())
  }
}
