// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductLookupResult

internal fun testBarcodeScanner() = BarcodeScanner { BarcodeScanResult.NotFound }

internal fun testProductCatalogRepository() =
    object : ProductCatalogRepository {
      override suspend fun lookupByBarcode(barcode: String) = ProductLookupResult.NotFound
    }
