// Co-authored-by: Codex
package com.android.sharemate.model.receipt

import android.content.Context
import com.android.sharemate.model.ocr.OnDeviceTextRecognizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts reviewable receipt products using on-device OCR. */
class ReceiptPhotoReader
internal constructor(
    private val recognizer: OnDeviceTextRecognizer,
    private val dispatcher: CoroutineDispatcher,
) : ReceiptReader {
  constructor(
      context: Context,
      dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(OnDeviceTextRecognizer(context, dispatcher), dispatcher)

  /** An empty list means that no reviewable products could be read. */
  override suspend fun read(uri: String): List<ReceiptItem> {
    val text = recognizer.recognize(uri)
    return withContext(dispatcher) {
      val lines =
          text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
              line.boundingBox?.let { box ->
                val words =
                    line.elements.mapNotNull { element ->
                      element.boundingBox?.let {
                        ReceiptWord(element.text, it.left, it.top, it.right, it.bottom)
                      }
                    }
                ReceiptLine(line.text, box.left, box.top, box.bottom, words)
              }
            }
          }
      ReceiptParser.parse(lines)
    }
  }
}
