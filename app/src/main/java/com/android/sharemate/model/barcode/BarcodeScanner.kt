// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.barcode

fun interface BarcodeScanner {
  suspend fun scan(imageUri: String): BarcodeScanResult

  fun close() {}
}

sealed interface BarcodeScanResult {
  data class Found(val value: String) : BarcodeScanResult

  data object NotFound : BarcodeScanResult
}
