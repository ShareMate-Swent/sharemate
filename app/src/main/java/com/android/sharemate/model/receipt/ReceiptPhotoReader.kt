// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ReceiptPhotoReader(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReceiptReader {
  private val appContext = context.applicationContext

  /** An empty list means that no reviewable products could be read. */
  override suspend fun read(uri: String): List<ReceiptItem> =
      withContext(dispatcher) {
        val image = InputImage.fromFilePath(appContext, Uri.parse(uri))
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
          val text = client.process(image).await()
          val lines =
              text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                  line.boundingBox?.let { ReceiptLine(line.text, it.left, it.top, it.bottom) }
                }
              }
          ReceiptParser.parse(lines)
        } finally {
          client.close()
        }
      }
}
