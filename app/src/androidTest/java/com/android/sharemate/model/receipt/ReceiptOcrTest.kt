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
  @Test
  fun readsProductNamesAndTaxCodedPricesFromAnImage() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("receipt", ".png", context.cacheDir)
    try {
      val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      val ink =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 36f
            typeface = Typeface.MONOSPACE
          }
      listOf("MARKET", "Knoblauch       0.90 1", "Asiago          3.15 2", "TOTAL           4.05")
          .forEachIndexed { index, row -> canvas.drawText(row, 40f, 60f + index * 80f, ink) }
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      assertEquals(
          listOf(ReceiptItem("Knoblauch", 90), ReceiptItem("Asiago", 315)),
          ReceiptPhotoReader(context).read(Uri.fromFile(file)))
    } finally {
      file.delete()
    }
    Unit
  }
}
