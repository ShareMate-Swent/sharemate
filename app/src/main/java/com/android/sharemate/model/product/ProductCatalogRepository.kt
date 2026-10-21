// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

interface ProductCatalogRepository {
  suspend fun lookupByBarcode(barcode: String): ProductLookupResult
}
