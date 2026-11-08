// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.StagedItem

/** Holds OCR results across Activity recreation; the photo ViewModel owns and cancels the read. */
class ReceiptScanViewModel(private val reader: ReceiptReader, private val saved: SavedStateHandle) :
    ViewModel() {
  /** Returns the current products synchronously when creating the staging ViewModel. */
  fun currentItems(): List<StagedItem> =
      saved.get<ArrayList<Bundle>>(ITEMS).orEmpty().map { it.toItem() }

  /** True until the active composition has opened the newly scanned products. */
  val needsReview = saved.getStateFlow(PENDING, false)
  /** True after confirmation; allows reopening the retained, edited staging list. */
  val reviewed = saved.getStateFlow(REVIEWED, false)

  /**
   * Reads [uri] in the caller's lifecycle coroutine; failure/cancellation leaves prior items
   * intact. [beforePublish] durably retains a successful parsed list before it replaces saved
   * state; failure in that callback also leaves prior items intact.
   */
  suspend fun read(uri: String, beforePublish: suspend (List<StagedItem>) -> Unit = {}) {
    val products = reader.read(uri)
    require(products.isNotEmpty()) { "No priced products found" }
    val items =
        products.mapIndexed { index, item ->
          StagedItem(index.toString(), item.name, priceCents = item.priceCents)
        }
    beforePublish(items)
    save(items)
    saved[REVIEWED] = false
    saved[PENDING] = true
  }

  /** Acknowledges navigation only after the current screen has opened the review destination. */
  fun reviewOpened() {
    saved[PENDING] = false
  }

  /** Stores edited [items] for reopening the review; no repository writes occur here. */
  fun confirm(items: List<StagedItem>) {
    save(items)
    saved[REVIEWED] = true
  }

  private fun save(items: List<StagedItem>) {
    saved[ITEMS] =
        ArrayList(
            items.map {
              bundleOf(ID to it.id, NAME to it.name, QUANTITY to it.quantity).apply {
                it.priceCents?.let { cents -> putLong(PRICE, cents) }
              }
            })
  }

  private fun Bundle.toItem() =
      StagedItem(
          getString(ID)!!,
          getString(NAME)!!,
          getInt(QUANTITY),
          if (containsKey(PRICE)) getLong(PRICE) else null)

  private companion object {
    const val ITEMS = "receiptItems"
    const val PENDING = "receiptReviewPending"
    const val REVIEWED = "receiptReviewed"
    const val ID = "id"
    const val NAME = "name"
    const val QUANTITY = "quantity"
    const val PRICE = "price"
  }
}
