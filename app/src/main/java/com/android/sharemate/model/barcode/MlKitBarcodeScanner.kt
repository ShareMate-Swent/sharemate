// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.barcode

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

class MlKitBarcodeScanner(context: Context) : BarcodeScanner {
  private val appContext = context.applicationContext
  private val scanner = lazy {
    BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E)
            .build())
  }

  override suspend fun scan(imageUri: String): BarcodeScanResult {
    val image = InputImage.fromFilePath(appContext, Uri.parse(imageUri))
    val value =
        scanner.value.process(image).await().firstNotNullOfOrNull {
          it.rawValue?.takeIf(String::isNotBlank)
        }
    return value?.let(BarcodeScanResult::Found) ?: BarcodeScanResult.NotFound
  }

  override fun close() {
    if (scanner.isInitialized()) scanner.value.close()
  }
}
