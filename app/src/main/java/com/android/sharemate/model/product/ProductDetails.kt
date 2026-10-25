// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

/** Product information returned by a catalog lookup. */
data class ProductDetails(
    /** The barcode used for the lookup, with leading zeroes preserved. */
    val barcode: String,
    /** Product name, or `null` when the catalog does not provide one. */
    val name: String?,
    /** Brand name, or `null` when the catalog does not provide one. */
    val brand: String?,
)
