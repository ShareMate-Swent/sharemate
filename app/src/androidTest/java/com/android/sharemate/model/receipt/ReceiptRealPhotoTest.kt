// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRealPhotoTest {
  @Test
  fun readsSampleTicket() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val file = java.io.File.createTempFile("receipt", ".jpg", context.cacheDir)
    try {
      instrumentation.context.assets.open("sroie-000.jpg").use { input ->
        file.outputStream().use { input.copyTo(it) }
      }
      val items = ReceiptPhotoReader(context).read(Uri.fromFile(file))
      val product = items.single()
      assertEquals(900L, product.priceCents)
      // This low-resolution scan can lose letters. Check the identifying words and exclude the SKU.
      assertTrue(product.name, product.name.startsWith("K") && product.name.contains("MODELLING"))
      assertTrue(product.name, product.name.endsWith("KIDDY FISH"))
    } finally {
      file.delete()
    }
    Unit
  }
}
