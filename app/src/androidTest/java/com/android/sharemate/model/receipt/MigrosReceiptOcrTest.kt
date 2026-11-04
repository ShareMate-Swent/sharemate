// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises ML Kit and row parsing with the real receipt shared in Inge's PR review. */
class MigrosReceiptOcrTest {
  @Test
  fun readsProductsAndTaxCodedPricesFromReviewReceipt() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val file = File.createTempFile("migros-receipt", ".jpg", context.cacheDir)
    try {
      instrumentation.context.assets.open("receipts/inge-migros-receipt.jpg").use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
      }
      val items = ReceiptPhotoReader(context).read(Uri.fromFile(file).toString())
      // These printed rows end in a separate tax digit, which must not hide their price.
      for (expected in
          listOf(
              ReceiptItem("Knoblauch", 90),
              ReceiptItem("Asiago", 315),
              ReceiptItem("Radieschen", 190))) {
        assertTrue("Missing $expected in OCR results: $items", expected in items)
      }
      // The printed TOTAL and payment/tax summaries are not groceries.
      assertTrue(items.none { it.name.contains("TOTAL", ignoreCase = true) })
    } finally {
      file.delete()
    }
    Unit
  }
}
