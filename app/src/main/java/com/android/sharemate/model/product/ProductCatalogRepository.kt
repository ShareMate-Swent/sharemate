// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

/** Looks up product details in an external catalog using a decoded barcode. */
interface ProductCatalogRepository {
  /** Returns the catalog match or an explicit not-found or failure result. */
  suspend fun lookupByBarcode(barcode: String): ProductLookupResult
}
