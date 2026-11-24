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
      InputImage::fromFilePath)

  private val appContext = context.applicationContext

  override suspend fun read(imageUri: String): List<LocalDate> =
      withContext(dispatcher) {
        val uri = Uri.parse(imageUri)
        require(uri.scheme == "content" || uri.scheme == "file") { "Expected a local image URI" }
        val image = imageFactory(appContext, uri)
        val recognizer = recognizerFactory()
        try {
          val text = withTimeout(RECOGNITION_TIMEOUT_MS) { recognizer.process(image).await() }
          ExpiryDateParser.findDates(text.text)
        } finally {
          recognizer.close()
        }
      }

  internal companion object {
    const val RECOGNITION_TIMEOUT_MS = 30_000L
  }
}
