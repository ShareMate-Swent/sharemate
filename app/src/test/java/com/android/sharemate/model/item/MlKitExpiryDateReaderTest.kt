// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MlKitExpiryDateReaderTest {
  private val appContext = mock(Context::class.java)
  private val context =
      mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(appContext) }
  private val image = mock(InputImage::class.java)
  private val client = mock(TextRecognizer::class.java)
  private val uri = "content://photos/expiry"
  private val date = LocalDate.of(2026, 10, 12)

  private fun recognized(text: String) =
      mock(Text::class.java).also { `when`(it.text).thenReturn(text) }

  private fun returnsText(recognizer: TextRecognizer, text: String) {
    val result = recognized(text)
    `when`(recognizer.process(image)).thenReturn(Tasks.forResult(result))
  }

  private fun assertFailure(expected: Exception, actual: Throwable?) {
    // Coroutine stack-trace recovery can copy exceptions across dispatcher boundaries.
    assertEquals(expected.javaClass, actual?.javaClass)
    assertEquals(expected.message, actual?.message)
  }

  @Test
  fun readsAllStoryFormatsInTextOrderWithoutChoosingADate() = runTest {
    returnsText(client, "MFD 11/10/26\nEXP/12/10/26\n12.10.2026\n12 OCT 2026\n31/02/26")
    var receivedContext: Context? = null
    var receivedUri: Uri? = null
    val reader: ExpiryDateReader =
        MlKitExpiryDateReader(
            context,
            StandardTestDispatcher(testScheduler),
            { client },
            { suppliedContext, suppliedUri ->
              receivedContext = suppliedContext
              receivedUri = suppliedUri
              image
            })

    assertEquals(listOf(LocalDate.of(2026, 10, 11), date), reader.read(uri))
    assertSame(appContext, receivedContext)
    assertEquals(Uri.parse(uri), receivedUri)
    verify(client).close()
  }

  @Test
  fun emptyUnsupportedAndInvalidTextReturnNoCandidates() = runTest {
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { client }, { _, _ -> image })
    for (text in listOf("", "BEST BEFORE see lid", "31/02/2026", "12/2026")) {
      returnsText(client, text)
      assertEquals(emptyList<LocalDate>(), reader.read(uri))
    }
    verify(client, times(4)).close()
  }

  @Test
  fun imageFailurePropagatesWithoutCreatingARecognizer() = runTest {
    val failure = IOException("unreadable image")
    val reader =
        MlKitExpiryDateReader(
            context,
            StandardTestDispatcher(testScheduler),
            { error("must not create recognizer") },
            { _, _ -> throw failure })
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
    verifyNoInteractions(client)
  }

  @Test
  fun remoteBlankAndRelativeUrisAreRejectedBeforeLoading() = runTest {
    val reader =
        MlKitExpiryDateReader(
            context,
            StandardTestDispatcher(testScheduler),
            { error("must not create recognizer") },
            { _, _ -> error("must not load image") })
    for (input in listOf("", "/tmp/image.png", "https://example.com/image", "ftp://host/image")) {
      assertTrue(runCatching { reader.read(input) }.exceptionOrNull() is IllegalArgumentException)
    }
  }

  @Test
  fun recognizerCreationFailurePropagates() = runTest {
    val failure = IllegalStateException("cannot initialize")
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { throw failure }, { _, _ -> image })
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
  }

  @Test
  fun synchronousAndAsynchronousOcrFailuresCloseClient() = runTest {
    val failure = IllegalStateException("recognition failed")
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { client }, { _, _ -> image })
    `when`(client.process(image)).thenReturn(Tasks.forException(failure))
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
    `when`(client.process(image)).thenThrow(failure)
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
    verify(client, times(2)).close()
  }

  @Test
  fun retryCreatesANewClientAfterFailure() = runTest {
    val next = mock(TextRecognizer::class.java)
    val clients = ArrayDeque(listOf(client, next))
    `when`(client.process(image)).thenReturn(Tasks.forException(IOException("failed")))
    returnsText(next, "12 OCT 2026")
    val reader =
        MlKitExpiryDateReader(
            context,
            StandardTestDispatcher(testScheduler),
            { clients.removeFirst() },
            { _, _ -> image })
    assertTrue(runCatching { reader.read(uri) }.exceptionOrNull() is IOException)
    assertEquals(listOf(date), reader.read(uri))
    verify(client).close()
    verify(next).close()
  }

  @Test
  fun cancellingOneReadDoesNotCloseAnotherReadsClient() = runTest {
    val next = mock(TextRecognizer::class.java)
    val pending = TaskCompletionSource<Text>()
    val otherPending = TaskCompletionSource<Text>()
    val clients = ArrayDeque(listOf(client, next))
    `when`(client.process(image)).thenReturn(pending.task)
    `when`(next.process(image)).thenReturn(otherPending.task)
    val reader =
        MlKitExpiryDateReader(
            context,
            StandardTestDispatcher(testScheduler),
            { clients.removeFirst() },
            { _, _ -> image })
    val first = async { reader.read(uri) }
    runCurrent()
    val second = async { reader.read(uri) }
    runCurrent()
    first.cancelAndJoin()
    verify(client).close()
    verify(next, never()).close()
    otherPending.setResult(recognized("12 OCT 2026"))
    runCurrent()
    assertEquals(listOf(date), second.await())
    verify(next).close()
  }

  @Test
  fun callerCancellationClosesClientAndLateResultIsIgnored() = runTest {
    val pending = TaskCompletionSource<Text>()
    `when`(client.process(image)).thenReturn(pending.task)
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { client }, { _, _ -> image })
    val read = async { reader.read(uri) }
    runCurrent()
    verify(client).process(image)
    read.cancelAndJoin()
    verify(client).close()
    pending.setResult(recognized("12 OCT 2026"))
    runCurrent()
    assertTrue(read.isCancelled)
  }

  @Test
  fun stalledRecognitionTimesOutAndClosesClient() = runTest {
    `when`(client.process(image)).thenReturn(TaskCompletionSource<Text>().task)
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { client }, { _, _ -> image })
    val read = async { runCatching { reader.read(uri) } }
    runCurrent()
    advanceTimeBy(MlKitExpiryDateReader.RECOGNITION_TIMEOUT_MS - 1)
    assertFalse(read.isCompleted)
    advanceTimeBy(1)
    runCurrent()
    assertTrue(read.await().exceptionOrNull() is CancellationException)
    verify(client).close()
  }

  @Test
  fun cancelledMlKitTaskPropagatesCancellationAndClosesClient() = runTest {
    `when`(client.process(image)).thenReturn(Tasks.forCanceled())
    val reader =
        MlKitExpiryDateReader(
            context, StandardTestDispatcher(testScheduler), { client }, { _, _ -> image })
    assertTrue(runCatching { reader.read(uri) }.exceptionOrNull() is CancellationException)
    verify(client).close()
  }

  @Test
  fun ioDispatcherLoadsFileOffCallerThread() {
    val caller = Thread.currentThread()
    var loadThread: Thread? = null
    returnsText(client, "12 OCT 2026")
    val reader =
        MlKitExpiryDateReader(
            context,
            kotlinx.coroutines.Dispatchers.IO,
            { client },
            { suppliedContext, suppliedUri ->
              assertSame(appContext, suppliedContext)
              assertEquals(Uri.parse("file:///local/expiry.png"), suppliedUri)
              loadThread = Thread.currentThread()
              image
            })
    assertEquals(listOf(date), runBlocking { reader.read("file:///local/expiry.png") })
    assertNotSame(caller, loadThread)
    verify(client).close()
  }

  @Test
  fun publicConstructorUsesFileLoaderAndLatinRecognizer() = runTest {
    returnsText(client, "12 OCT 2026")
    mockStatic(InputImage::class.java).use { images ->
      mockStatic(TextRecognition::class.java).use { recognition ->
        images
            .`when`<InputImage> { InputImage.fromFilePath(appContext, Uri.parse(uri)) }
            .thenReturn(image)
        recognition
            .`when`<TextRecognizer> {
              TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            .thenReturn(client)
        assertEquals(
            listOf(date),
            MlKitExpiryDateReader(context, StandardTestDispatcher(testScheduler)).read(uri))
        recognition.verify { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
      }
    }
    verify(client).close()
  }
}
