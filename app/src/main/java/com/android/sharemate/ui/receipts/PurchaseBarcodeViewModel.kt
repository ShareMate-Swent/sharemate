// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.limitedTo
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

enum class PurchaseBarcodeError {
  UNREADABLE,
  NOT_FOUND,
  LOOKUP_FAILED,
  NO_DETAILS
}

/** Resolves one photo handoff per deliberate scan; catalog prices are never purchase prices. */
class PurchaseBarcodeViewModel(
    private val scanner: BarcodeScanner,
    private val catalog: ProductCatalogRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
  private val mutableError = MutableStateFlow<PurchaseBarcodeError?>(null)
  val error = mutableError.asStateFlow()
  private var busy = false

  fun startScan() {
    saved[SESSION] = UUID.randomUUID().toString()
    mutableError.value = null
  }

  fun cancelScan() {
    saved[SESSION] = null
    mutableError.value = null
  }

  /** Null means this handoff was replayed, cancelled or superseded, and must not append a row. */
  suspend fun read(uri: String): StagedItem? {
    val token = saved.get<String>(SESSION) ?: return null
    if (busy || saved.get<String>(COMPLETED) == token) return null
    busy = true
    mutableError.value = null
    try {
      val item =
          withTimeout(LOOKUP_TIMEOUT_MS) {
            val barcode = (scanner.scan(uri) as? BarcodeScanResult.Found)?.value?.trim()
            if (barcode.isNullOrEmpty()) throw LookupFailure(PurchaseBarcodeError.UNREADABLE)
            when (val result = catalog.lookupByBarcode(barcode)) {
              is ProductLookupResult.Found -> {
                val name =
                    (result.product.name?.takeIf { it.isNotBlank() } ?: result.product.brand)
                        ?.trim()
                        ?.limitedTo(TextLimits.ITEM_NAME)
                        ?.trimEnd()
                if (name.isNullOrEmpty()) throw LookupFailure(PurchaseBarcodeError.NO_DETAILS)
                StagedItem(token, name, priceCents = null)
              }
              ProductLookupResult.NotFound -> throw LookupFailure(PurchaseBarcodeError.NOT_FOUND)
              is ProductLookupResult.Failure ->
                  throw LookupFailure(PurchaseBarcodeError.LOOKUP_FAILED)
            }
          }
      currentCoroutineContext().ensureActive()
      if (saved.get<String>(SESSION) != token) return null
      saved[COMPLETED] = token
      return item
    } catch (_: TimeoutCancellationException) {
      if (saved.get<String>(SESSION) != token) return null
      mutableError.value = PurchaseBarcodeError.LOOKUP_FAILED
      throw IllegalStateException("Product lookup timed out")
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      if (saved.get<String>(SESSION) != token) return null
      mutableError.value = (failure as? LookupFailure)?.error ?: PurchaseBarcodeError.LOOKUP_FAILED
      throw failure
    } finally {
      busy = false
    }
  }

  private class LookupFailure(val error: PurchaseBarcodeError) : IllegalStateException(error.name)

  override fun onCleared() {
    cancelScan()
    scanner.close()
  }

  private companion object {
    const val SESSION = "purchaseBarcodeSession"
    const val COMPLETED = "purchaseBarcodeCompleted"
    const val LOOKUP_TIMEOUT_MS = 15_000L
  }
}
