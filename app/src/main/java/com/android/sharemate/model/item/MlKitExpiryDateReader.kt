// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import android.content.Context
import com.android.sharemate.model.ocr.OnDeviceTextRecognizer
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts candidate expiry dates from on-device OCR. Repairs the observed month token 0CT between
 * a day and year. All returned dates still require user confirmation.
 */
class MlKitExpiryDateReader
internal constructor(
    private val recognizer: OnDeviceTextRecognizer,
    private val dispatcher: CoroutineDispatcher,
) : ExpiryDateReader {
  /** Creates an on-device reader retaining only the application context. */
  constructor(
      context: Context,
      dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(OnDeviceTextRecognizer(context, dispatcher), dispatcher)

  override suspend fun read(imageUri: String): List<LocalDate> {
    val text = recognizer.recognize(imageUri)
    return withContext(dispatcher) {
      val candidateText = misreadOctober.replace(text.text, "$1OCT")
      ExpiryDateParser.findDates(candidateText)
    }
  }

  private companion object {
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
