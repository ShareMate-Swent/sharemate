// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.ExpiryDateReader
import java.time.LocalDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Holds the latest scan result until the item form uses or dismisses it. */
internal class ExpiryScanViewModel(private val reader: ExpiryDateReader) : ViewModel() {
  /** Whether this form session opened the picker. Survives rotation with the form. */
  var pickerOpened = false
    private set

  /** Associates the picker with the currently open item form. */
  fun openPicker() {
    pickerOpened = true
  }

  private val result = MutableStateFlow<List<LocalDate>?>(null)

  /** A null result is idle. An empty list represents a scan with no supported date. */
  val candidates = result.asStateFlow()

  /**
   * Scans the picker's private URI and propagates failures so the preview can offer a retry. Waits
   * for cancellation before returning so the picker can safely release the image.
   */
  suspend fun scan(uri: String) {
    val read = viewModelScope.async { reader.read(uri) }
    try {
      result.value = read.await()
    } finally {
      withContext(NonCancellable) { read.cancelAndJoin() }
    }
  }

  /** Clears the result when it is used or the form is closed. */
  fun clear() {
    result.value = null
    pickerOpened = false
  }
}
