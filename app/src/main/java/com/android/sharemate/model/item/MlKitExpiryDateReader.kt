// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Uses the existing bundled Latin text model, available offline without a first-use download. Image
 * decoding and date parsing run on [dispatcher]. Each call owns and closes its recognizer,
 * including on failure/cancellation; simultaneous reads do not share a client. Recognition is
 * limited to [RECOGNITION_TIMEOUT_MS]; timeout propagates as coroutine cancellation. Synchronous
 * image decoding cannot be interrupted by the recognition timeout.
 *
 * Repairs the observed OCR month token `0CT` to `OCT` only between a day and year. Other digits are
 * not guessed or changed. All returned dates still require user confirmation.
 */
class MlKitExpiryDateReader
internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
    private val recognizerFactory: () -> TextRecognizer,
    private val imageFactory: (Context, Uri) -> InputImage,
) : ExpiryDateReader {
  /** Creates an on-device reader retaining only the application context. */
  constructor(
      context: Context,
      dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
      context,
      dispatcher,
      { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
      InputImage::fromFilePath,
  )

  private val appContext = context.applicationContext

  override suspend fun read(imageUri: String): List<LocalDate> =
      withContext(dispatcher) {
        val uri = Uri.parse(imageUri)
        require(uri.scheme == "content" || uri.scheme == "file") { "Expected a local image URI" }
        val image = imageFactory(appContext, uri)
        val recognizer = recognizerFactory()
        try {
          val text = withTimeout(RECOGNITION_TIMEOUT_MS) { recognizer.process(image).await() }
          val candidateText = misreadOctober.replace(text.text, "\$1OCT")
          ExpiryDateParser.findDates(candidateText)
        } finally {
          recognizer.close()
        }
      }

  internal companion object {
    const val RECOGNITION_TIMEOUT_MS = 30_000L

    // Observed ML Kit confusion: the printed letter O is read as zero. Repair only a complete
    // October abbreviation in a supported named-month date.
    private val misreadOctober =
        Regex(
            "(?<![\\p{L}\\p{N}])([0-9]{1,2}[\\p{Zs}\\t]+)0CT" +
                "(?=\\.?[\\p{Zs}\\t]+(?:[0-9]{4}|[0-9]{2})(?![\\p{L}\\p{N}]))",
            RegexOption.IGNORE_CASE,
        )
  }
}
