// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptOcrTest {
  private companion object {
    const val IMAGE_WIDTH = 800
    const val IMAGE_HEIGHT = 400
    const val TEXT_SIZE = 36f
    const val LEFT_MARGIN = 40f
    const val FIRST_BASELINE = 60f
    const val ROW_HEIGHT = 80f
    const val PNG_QUALITY_IGNORED = 100
  }

  @Test
  fun readsProductNamesAndTaxCodedPricesFromAnImage() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("receipt", ".png", context.cacheDir)
    try {
      val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      val ink =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.MONOSPACE
          }
      listOf("MARKET", "Knoblauch       0.90 1", "Asiago          3.15 2", "TOTAL           4.05")
          .forEachIndexed { index, row ->
            canvas.drawText(row, LEFT_MARGIN, FIRST_BASELINE + index * ROW_HEIGHT, ink)
          }
      file.outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, it)
      }
      assertEquals(
          listOf(ReceiptItem("Knoblauch", 90), ReceiptItem("Asiago", 315)),
          ReceiptPhotoReader(context).read(Uri.fromFile(file).toString()))
    } finally {
      file.delete()
    }
    Unit
  }
}
