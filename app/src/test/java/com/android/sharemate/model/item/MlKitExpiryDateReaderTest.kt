// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import android.net.Uri
import android.os.Looper
import com.android.sharemate.model.ocr.OnDeviceTextRecognizer
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MlKitExpiryDateReaderTest {
  private val image = mock(InputImage::class.java)
  private val client = mock(TextRecognizer::class.java)
  private val uri = "content://photos/expiry"
  private val date = LocalDate.of(2026, 10, 12)

  private fun readerWithOcr(
      dispatcher: CoroutineDispatcher,
      factory: () -> TextRecognizer,
      load: (Uri) -> InputImage,
  ) =
      MlKitExpiryDateReader(
          OnDeviceTextRecognizer(dispatcher, factory, load),
          dispatcher,
      )

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
  fun repairsObservedOctoberOcrTokenWithoutChangingThePureParser() = runTest {
    returnsText(client, "MFD 11/10/26\nEXP 12/10/26\nEXP 13.10.2026\nEXP 14 0CT 2026")
    val reader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { client },
            { _ -> image },
        )

    assertEquals(
        listOf(
            LocalDate.of(2026, 10, 11),
            date,
            LocalDate.of(2026, 10, 13),
            LocalDate.of(2026, 10, 14),
        ),
        reader.read(uri),
    )
    assertTrue(ExpiryDateParser.findDates("14 0CT 2026").isEmpty())
    verify(client).close()
  }

  @Test
  fun repairsOctoberAtValidDayBoundariesWithSupportedSpacingAndYears() = runTest {
    returnsText(client, "EXP/1\t0ct.\u00a026\n31 0Ct 2026")
    val reader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { client },
            { _ -> image },
        )

    assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), reader.read(uri))
    verify(client).close()
  }

  @Test
  fun octoberRepairDoesNotGuessDigitsOrAcceptInvalidOrEmbeddedDateTokens() = runTest {
    val inputs =
        listOf(
            "0CT 2026",
            "14 0CT",
            "A14 0CT 2026",
            "14 A0CT 2026",
            "14 0CTX 2026",
            "0 0CT 2026",
            "32 0CT 2026",
            "014 0CT 2026",
            "14 0CT 0000",
            "14 0CT 20267",
            "14 0CT 2O26",
            "14\n0CT 2026",
            "14 0CT\n2026",
            "14 0C7 2026",
            "1O/1O/26",
        )
    val reader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { client },
            { _ -> image },
        )
    for (text in inputs) {
      returnsText(client, text)
      assertEquals(text, emptyList<LocalDate>(), reader.read(uri))
    }
    verify(client, times(inputs.size)).close()
  }

  @Test
  fun readsAllStoryFormatsInTextOrderWithoutChoosingADate() = runTest {
    returnsText(client, "MFD 11/10/26\nEXP/12/10/26\n12.10.2026\n12 OCT 2026\n31/02/26")
    var receivedUri: Uri? = null
    val reader: ExpiryDateReader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { client },
            { suppliedUri ->
              receivedUri = suppliedUri
              image
            })

    assertEquals(listOf(LocalDate.of(2026, 10, 11), date), reader.read(uri))
    assertEquals(Uri.parse(uri), receivedUri)
    verify(client).close()
  }

  @Test
  fun emptyUnsupportedAndInvalidTextReturnNoCandidates() = runTest {
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
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
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { error("must not create recognizer") },
            { _ -> throw failure })
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
    verifyNoInteractions(client)
  }

  @Test
  fun invalidImageUrisAreRejectedBeforeLoading() = runTest {
    val reader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler),
            { error("must not create recognizer") },
            { _ -> error("must not load image") })
    val invalidUris =
        listOf(
            "",
            "/tmp/image.png",
            "https://example.com/image",
            "ftp://host/image",
            "content:///image.png",
            "file://host/image.png",
            "file:relative.png",
        )
    for (input in invalidUris) {
      assertTrue(runCatching { reader.read(input) }.exceptionOrNull() is IllegalArgumentException)
    }
  }

  @Test
  fun recognizerCreationFailurePropagates() = runTest {
    val failure = IllegalStateException("cannot initialize")
    val reader =
        readerWithOcr(StandardTestDispatcher(testScheduler), { throw failure }, { _ -> image })
    assertFailure(failure, runCatching { reader.read(uri) }.exceptionOrNull())
  }

  @Test
  fun synchronousAndAsynchronousOcrFailuresCloseClient() = runTest {
    val failure = IllegalStateException("recognition failed")
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
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
        readerWithOcr(
            StandardTestDispatcher(testScheduler), { clients.removeFirst() }, { _ -> image })
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
        readerWithOcr(
            StandardTestDispatcher(testScheduler), { clients.removeFirst() }, { _ -> image })
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
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
    val read = async { reader.read(uri) }
    runCurrent()
    verify(client).process(image)
    read.cancelAndJoin()
    verify(client).close()
    pending.setResult(recognized("12 OCT 2026"))
    runCurrent()
    assertTrue(read.isCancelled)
    assertTrue(runCatching { read.await() }.exceptionOrNull() is CancellationException)
  }

  @Test
  fun stalledRecognitionTimesOutAndClosesClient() = runTest {
    `when`(client.process(image)).thenReturn(TaskCompletionSource<Text>().task)
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
    val read = async { runCatching { reader.read(uri) } }
    runCurrent()
    advanceTimeBy(OnDeviceTextRecognizer.RECOGNITION_TIMEOUT_MS - 1)
    assertFalse(read.isCompleted)
    advanceTimeBy(1)
    runCurrent()
    assertFailure(IOException("Text recognition timed out"), read.await().exceptionOrNull())
    verify(client).close()
  }

  @Test
  fun cancelledMlKitTaskPropagatesFailureAndClosesClient() = runTest {
    `when`(client.process(image)).thenReturn(Tasks.forCanceled())
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
    assertFailure(
        IOException("Text recognition was cancelled"),
        runCatching { reader.read(uri) }.exceptionOrNull())
    verify(client).close()
  }

  @Test
  fun mlKitTaskCancelledWhileAwaitingIsAFailureAndAllowsRetry() = runTest {
    val cancellation = CancellationTokenSource()
    val pending = TaskCompletionSource<Text>(cancellation.token)
    val next = mock(TextRecognizer::class.java)
    val clients = ArrayDeque(listOf(client, next))
    `when`(client.process(image)).thenReturn(pending.task)
    returnsText(next, "12 OCT 2026")
    val reader =
        readerWithOcr(
            StandardTestDispatcher(testScheduler), { clients.removeFirst() }, { _ -> image })
    val read = async { runCatching { reader.read(uri) } }
    runCurrent()
    assertFalse(read.isCompleted)
    cancellation.cancel()
    shadowOf(Looper.getMainLooper()).idle()
    runCurrent()
    val failure = read.await().exceptionOrNull()
    assertFailure(IOException("Text recognition was cancelled"), failure)
    // Coroutine stack-trace recovery can wrap the original IOException in another IOException.
    assertTrue(generateSequence(failure) { it.cause }.any { it is CancellationException })
    verify(client).close()
    assertEquals(listOf(date), reader.read(uri))
    verify(next).close()
  }

  @Test
  fun shorterCallerTimeoutRemainsCancellationAndClosesClient() = runTest {
    `when`(client.process(image)).thenReturn(TaskCompletionSource<Text>().task)
    val reader = readerWithOcr(StandardTestDispatcher(testScheduler), { client }, { _ -> image })
    val read = async { runCatching { withTimeout(1) { reader.read(uri) } } }
    runCurrent()
    advanceTimeBy(1)
    runCurrent()
    assertTrue(read.await().exceptionOrNull() is TimeoutCancellationException)
    verify(client).close()
  }

  @Test
  fun ioDispatcherLoadsFileOffCallerThread() {
    val caller = Thread.currentThread()
    var loadThread: Thread? = null
    returnsText(client, "12 OCT 2026")
    val reader =
        readerWithOcr(
            kotlinx.coroutines.Dispatchers.IO,
            { client },
            { suppliedUri ->
              assertEquals(Uri.parse("file:///local/expiry.png"), suppliedUri)
              loadThread = Thread.currentThread()
              image
            })
    assertEquals(listOf(date), runBlocking { reader.read("file:///local/expiry.png") })
    assertNotSame(caller, loadThread)
    verify(client).close()
  }
}
