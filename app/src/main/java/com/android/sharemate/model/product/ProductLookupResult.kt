// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

sealed interface ProductLookupResult {
  data class Found(val product: ProductDetails) : ProductLookupResult

  data object NotFound : ProductLookupResult

  data class Failure(val reason: Reason, val statusCode: Int? = null) : ProductLookupResult

  enum class Reason {
    INVALID_BARCODE,
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
  }
}
