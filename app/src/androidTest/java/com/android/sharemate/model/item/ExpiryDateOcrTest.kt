// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the bundled recognizer and real image decoding, without camera or Firebase services.
 */
class ExpiryDateOcrTest {
  private companion object {
    const val IMAGE_WIDTH = 1000
    const val IMAGE_HEIGHT = 500
    const val TEXT_SIZE = 48f
    const val MARGIN = 40f
    const val FIRST_BASELINE = 80f
    const val ROW_HEIGHT = 100f
    const val PNG_QUALITY_IGNORED = 100
  }

  @Test
  fun recognizesStoryFormatsAndKeepsCandidatesForUserReview() = runBlocking {
    withImage(listOf("MFD 11/10/26", "EXP 12/10/26", "EXP 13.10.2026", "EXP 14 OCT 2026")) {
      assertEquals(
          listOf(
              LocalDate.of(2026, 10, 11),
              LocalDate.of(2026, 10, 12),
              LocalDate.of(2026, 10, 13),
              LocalDate.of(2026, 10, 14)),
          it)
    }
  }

  @Test
  fun noTextAndInvalidDatesReturnNoCandidates() = runBlocking {
    withImage(emptyList()) { assertTrue(it.isEmpty()) }
    withImage(listOf("BEST BEFORE see lid", "31/02/2026")) { assertTrue(it.isEmpty()) }
  }

  private suspend fun withImage(lines: List<String>, check: (List<LocalDate>) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("expiry-ocr", ".png", context.cacheDir)
    val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      val ink =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.MONOSPACE
          }
      lines.forEachIndexed { index, line ->
        canvas.drawText(line, MARGIN, FIRST_BASELINE + index * ROW_HEIGHT, ink)
      }
      file.outputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, it))
      }
      check(MlKitExpiryDateReader(context).read(Uri.fromFile(file).toString()))
      assertTrue("Reading must preserve the caller's image", file.exists())
    } finally {
      bitmap.recycle()
      file.delete()
    }
  }

  /** Temporary diagnostic on the isolated probe branch; original PR tests remain unchanged. */
  @Test
  fun compareRawOcrFromMonospaceAndSansSerif() = runBlocking {
    val mono = readDiagnosticFixture(Typeface.MONOSPACE)
    val sans = readDiagnosticFixture(Typeface.SANS_SERIF)
    val expected = listOf(
        LocalDate.of(2026, 10, 11),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 13),
        LocalDate.of(2026, 10, 14))
    assertEquals(
        "MONOSPACE RAW: [${mono.first}] CANDIDATES: ${mono.second}\n" +
            "SANS_SERIF RAW: [${sans.first}] CANDIDATES: ${sans.second}",
        listOf(expected, expected),
        listOf(mono.second, sans.second))
  }

  private suspend fun readDiagnosticFixture(font: Typeface): Pair<String, List<LocalDate>> =
      withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("synthetic-expiry-probe-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
          val canvas = Canvas(bitmap)
          canvas.drawColor(Color.WHITE)
          val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = font
          }
          listOf("MFD 11/10/26", "EXP 12/10/26", "EXP 13.10.2026", "EXP 14 OCT 2026")
              .forEachIndexed { index, line ->
                canvas.drawText(line, MARGIN, FIRST_BASELINE + index * ROW_HEIGHT, ink)
              }
          file.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, it))
          }
          val text = recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(file))).await().text
          text to ExpiryDateParser.findDates(text)
        } finally {
          recognizer.close()
          bitmap.recycle()
          file.delete()
        }
      }
}
