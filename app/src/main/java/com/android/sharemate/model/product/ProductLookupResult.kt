// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

/** Outcome of looking up product details by barcode. */
sealed interface ProductLookupResult {
  /** A product was found; individual catalog fields may be unavailable. */
  data class Found(val product: ProductDetails) : ProductLookupResult

  /** The catalog confirms that no product matches the barcode. */
  data object NotFound : ProductLookupResult

  /** The lookup failed for [reason], optionally with the received HTTP status code. */
  data class Failure(val reason: Reason, val statusCode: Int? = null) : ProductLookupResult

  /** Reason a product lookup failed. */
  enum class Reason {
    /** The barcode is blank, malformed, or outside the supported length. */
    INVALID_BARCODE,

    /** The scanner could not process the image because of a technical failure. */
    SCANNER_FAILURE,

    /** The catalog could not be reached. */
    NETWORK,

    /** The catalog returned an unsuccessful HTTP status. */
    HTTP,

    /** The catalog response could not be parsed or had an unexpected format. */
    INVALID_RESPONSE,
  }
}
