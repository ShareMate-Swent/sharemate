// Co-authored-by: Codex
package com.android.sharemate.ui.receipts

import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.model.receipt.ReceiptItem
import com.android.sharemate.model.receipt.ReceiptLine
import com.android.sharemate.model.receipt.ReceiptParser
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.staging.StagingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptScanViewModelTest {
  @Test
  fun parsedQuantitiesAndRowTotalsSurviveReviewCorrectionsAndRecreation() = runTest {
    val parsed =
        ReceiptParser.parse(
            listOf(
                ReceiptLine("Lait 2 x 2.40 4.80", 0, 0, 10),
                ReceiptLine("Pommes 0.350 kg @4.00/kg", 0, 20, 30)))
    val saved = SavedStateHandle()
    val model = ReceiptScanViewModel(ReceiptReader { parsed }, saved)
    model.read("content://receipt")
    assertEquals(
        listOf(StagedItem("0", "Lait", 2, 480), StagedItem("1", "Pommes 0.350 kg", 1, null)),
        model.currentItems())
    val beforeEditing =
        ReceiptScanViewModel(
            ReceiptReader { error("Do not rerun OCR on restoration") },
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
    assertEquals(model.currentItems(), beforeEditing.currentItems())
    val staging = StagingViewModel(beforeEditing.currentItems())
    staging.startEditing("0")
    staging.updateDraftQuantity("3")
    staging.updateDraftPrice("7.20")
    staging.saveDraft()
    staging.startEditing("1")
    staging.updateDraftPrice("1.40")
    staging.saveDraft()
    val corrected = checkNotNull(staging.confirmedItems())
    assertEquals(
        listOf(StagedItem("0", "Lait", 3, 720), StagedItem("1", "Pommes 0.350 kg", 1, 140)),
        corrected)
    model.confirm(corrected)
    val restored =
        ReceiptScanViewModel(
            ReceiptReader { error("Do not parse over corrections") },
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
    assertEquals(corrected, restored.currentItems())
    assertTrue(restored.reviewed.value)
  }

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
