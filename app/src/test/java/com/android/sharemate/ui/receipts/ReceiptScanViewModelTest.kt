// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.StagedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptScanViewModelTest {
  @Test
  fun successfulReadRequestsReviewAndKeepsEditedValuesWhenRestored() = runTest {
    val saved = SavedStateHandle()
    val model = ReceiptScanViewModel(ReceiptReader { listOf(ReceiptItem("Milk", 240)) }, saved)
    model.read("content://receipt")
    assertEquals(listOf(StagedItem("0", "Milk", priceCents = 240)), model.currentItems())
    assertTrue(model.needsReview.value)
    model.reviewOpened()
    assertFalse(model.needsReview.value)
    val edited = listOf(StagedItem("0", "Oat milk", 2, null))
    model.confirm(edited)
    val restored =
        ReceiptScanViewModel(
            ReceiptReader { error("Not needed") },
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
    assertEquals(edited, restored.currentItems())
    assertTrue(restored.reviewed.value)
  }

  @Test
  fun failuresAndCancellationKeepPreviouslyReviewedProducts() = runTest {
    for (failure in
        listOf<Throwable?>(null, IllegalStateException("unreadable"), CancellationException())) {
      val model =
          ReceiptScanViewModel(
              ReceiptReader { if (failure != null) throw failure else emptyList() },
              SavedStateHandle())
      val previous = listOf(StagedItem("old", "Bread", priceCents = 310))
      model.confirm(previous)
      try {
        model.read("content://receipt")
        fail("Expected failure")
      } catch (_: Exception) {}
      assertEquals(previous, model.currentItems())
      assertFalse(model.needsReview.value)
      assertTrue(model.reviewed.value)
    }
  }
}
