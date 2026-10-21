// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.barcode

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitBarcodeClient
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

class MlKitBarcodeScanner
internal constructor(
    context: Context,
    private val scannerFactory: () -> MlKitBarcodeClient,
    private val imageFactory: (Context, Uri) -> InputImage,
) : BarcodeScanner {
  constructor(context: Context) : this(context, ::createBarcodeClient, InputImage::fromFilePath)

  private val appContext = context.applicationContext
  private val scanner = lazy(scannerFactory)

  override suspend fun scan(imageUri: String): BarcodeScanResult {
    val image = imageFactory(appContext, Uri.parse(imageUri))
    val value =
        scanner.value.process(image).await().firstNotNullOfOrNull {
          it.rawValue?.takeIf(String::isNotBlank)
        }
    return value?.let(BarcodeScanResult::Found) ?: BarcodeScanResult.NotFound
  }

  override fun close() {
    if (scanner.isInitialized()) scanner.value.close()
  }

  companion object {
    internal fun createBarcodeClient(): MlKitBarcodeClient =
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E)
                .build())
  }
}
