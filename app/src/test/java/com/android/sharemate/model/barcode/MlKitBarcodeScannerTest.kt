// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.barcode

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitClient
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MlKitBarcodeScannerTest {
  private val context =
      mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }
  private val image = mock(InputImage::class.java)

  @Test
  fun scanReturnsFirstNonBlankBarcodeAndUsesProvidedImageUri() {
    val client = mock(MlKitClient::class.java)
    val noValue = mock(Barcode::class.java)
    val blankValue = mock(Barcode::class.java)
    val match = mock(Barcode::class.java)
    val laterMatch = mock(Barcode::class.java)
    `when`(noValue.rawValue).thenReturn(null)
    `when`(blankValue.rawValue).thenReturn("  ")
    `when`(match.rawValue).thenReturn("0123456789012")
    `when`(laterMatch.rawValue).thenReturn("9876543210987")
    `when`(client.process(image))
        .thenReturn(Tasks.forResult(listOf(noValue, blankValue, match, laterMatch)))
    var receivedContext: Context? = null
    var receivedUri: Uri? = null
    val scanner =
        MlKitBarcodeScanner(context, { client }) { suppliedContext, suppliedUri ->
          receivedContext = suppliedContext
          receivedUri = suppliedUri
          image
        }

    val result = kotlinx.coroutines.runBlocking { scanner.scan("content://photos/barcode") }

    assertEquals(BarcodeScanResult.Found("0123456789012"), result)
    assertSame(context, receivedContext)
    assertEquals(Uri.parse("content://photos/barcode"), receivedUri)
    verify(client).process(image)
  }

  @Test
  fun scanReturnsNotFoundWhenNoBarcodeHasAReadableValue() {
    val client = mock(MlKitClient::class.java)
    val blankValue = mock(Barcode::class.java)
    `when`(blankValue.rawValue).thenReturn(" ")
    `when`(client.process(image)).thenReturn(Tasks.forResult(listOf(blankValue)))
    val scanner = MlKitBarcodeScanner(context, { client }) { _, _ -> image }

    val result = kotlinx.coroutines.runBlocking { scanner.scan("content://photos/blank") }

    assertEquals(BarcodeScanResult.NotFound, result)
  }

  @Test
  fun scanPropagatesMlKitTaskFailures() {
    val client = mock(MlKitClient::class.java)
    val failure = IllegalStateException("ML Kit failed")
    `when`(client.process(image)).thenReturn(Tasks.forException(failure))
    val scanner = MlKitBarcodeScanner(context, { client }) { _, _ -> image }

    try {
      kotlinx.coroutines.runBlocking { scanner.scan("content://photos/error") }
      throw AssertionError("Scanner failures must propagate")
    } catch (actual: IllegalStateException) {
      assertSame(failure, actual)
    }
  }

  @Test
  fun scanPropagatesImageLoadingFailures() {
    val failure = IllegalArgumentException("Image URI cannot be opened")
    val scanner =
        MlKitBarcodeScanner(context, { error("Scanner must not be created") }) { _, _ ->
          throw failure
        }

    try {
      kotlinx.coroutines.runBlocking { scanner.scan("content://photos/missing") }
      throw AssertionError("Image loading failures must propagate")
    } catch (actual: IllegalArgumentException) {
      assertSame(failure, actual)
    }
  }

  @Test
  fun scannerIsCreatedLazilyAndClosedOnlyAfterInitialization() {
    val client = mock(MlKitClient::class.java)
    `when`(client.process(image)).thenReturn(Tasks.forResult(emptyList()))
    var factoryCalls = 0
    val scanner =
        MlKitBarcodeScanner(
            context,
            {
              factoryCalls++
              client
            }) { _, _ ->
              image
            }

    scanner.close()
    assertEquals(0, factoryCalls)
    kotlinx.coroutines.runBlocking { scanner.scan("content://photos/barcode") }
    kotlinx.coroutines.runBlocking { scanner.scan("content://photos/another-barcode") }
    assertEquals(1, factoryCalls)
    scanner.close()
    verify(client).close()
  }

  @Test
  fun defaultConstructorDoesNotInitializeMlKitUntilUsed() {
    val scanner = MlKitBarcodeScanner(context)

    scanner.close()
  }

  @Test
  fun configuredClientSupportsStandardEanAndUpcFormats() {
    val client = mock(MlKitClient::class.java)
    val options = ArgumentCaptor.forClass(BarcodeScannerOptions::class.java)
    mockStatic(BarcodeScanning::class.java).use { barcodeScanning ->
      barcodeScanning
          .`when`<MlKitClient> { BarcodeScanning.getClient(any(BarcodeScannerOptions::class.java)) }
          .thenReturn(client)

      MlKitBarcodeScanner.createBarcodeClient()
      barcodeScanning.verify { BarcodeScanning.getClient(options.capture()) }
    }

    val expectedOptions =
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E)
            .build()
    assertEquals(expectedOptions, options.value)
  }
}
