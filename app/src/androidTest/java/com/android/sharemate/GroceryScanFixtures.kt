package com.android.sharemate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Synthetic barcode sent through the normal gallery importer and recognizer. */
internal class GroceryScanFixtures(private val context: Context) : AutoCloseable {
  private val directory = File(context.cacheDir, "test-fixtures")
  private val file = File(directory, "olive-oil-barcode.png")

  fun barcode(): Uri {
    check(directory.isDirectory || directory.mkdirs())
    val bitmap = Bitmap.createBitmap(700, 400, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      val ink = Paint().apply { color = Color.BLACK }
      EAN_MODULES.forEachIndexed { index, module ->
        if (module == '1') {
          val left = 65f + index * 6
          canvas.drawRect(left, 35f, left + 6f, 335f, ink)
        }
      }
      file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
      bitmap.recycle()
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.test-fixtures", file)
  }

  override fun close() {
    file.delete()
  }

  companion object {
    const val OLIVE_OIL_EAN = "7617027160964"
    // Fixed 95-module EAN-13 fixture including the start, center and end guard bars.
    private const val EAN_MODULES =
        "10101011110110011011101101001110010011001000101010110011010100001110010111010010100001011100101"
  }
}
