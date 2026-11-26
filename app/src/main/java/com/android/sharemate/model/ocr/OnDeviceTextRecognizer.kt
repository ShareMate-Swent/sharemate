// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.ocr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recognizes text with the bundled Latin model. Image access failures, recognition timeouts and
 * internal task cancellations fail the read. Caller cancellation propagates to the caller.
 */
internal class OnDeviceTextRecognizer(
    private val dispatcher: CoroutineDispatcher,
    private val recognizerFactory: () -> TextRecognizer,
    private val loadImage: suspend (Uri) -> InputImage,
) {
  constructor(
      context: Context,
      dispatcher: CoroutineDispatcher = Dispatchers.IO
  ) : this(
      dispatcher,
      { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
      bitmapImageLoader(context.applicationContext),
  )

  suspend fun recognize(imageUri: String): Text =
      withContext(dispatcher) {
        val uri = Uri.parse(imageUri)
        require(
            (uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank()) ||
                (uri.scheme == ContentResolver.SCHEME_FILE &&
                    uri.authority.isNullOrEmpty() &&
                    uri.path?.startsWith("/") == true)) {
              "Expected a content or local file image URI"
            }

        val image = loadImage(uri)
        currentCoroutineContext().ensureActive()
        val recognizer = recognizerFactory()
        try {
          withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
            try {
              recognizer.process(image).await()
            } catch (cancelled: CancellationException) {
              currentCoroutineContext().ensureActive()
              throw IOException("Text recognition was cancelled", cancelled)
            }
          } ?: throw IOException("Text recognition timed out")
        } finally {
          recognizer.close()
        }
      }

  internal companion object {
    const val RECOGNITION_TIMEOUT_MS = 30_000L

    private fun bitmapImageLoader(context: Context): suspend (Uri) -> InputImage {
      val loader = BoundedOcrImageLoader(context)
      return { uri -> InputImage.fromBitmap(loader.load(uri), 0) }
    }
  }
}
