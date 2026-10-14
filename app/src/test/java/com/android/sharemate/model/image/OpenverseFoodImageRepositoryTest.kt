// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenverseFoodImageRepositoryTest {
  @Test
  fun searchReturnsImageAndAttributionOffTheCallingThread() = runBlocking {
    val connection = SimulatedCall()
    val callingThread = Thread.currentThread()

    val result = repository(connection).searchByTitle("  soupe à l'oignon & pain  ")

    assertEquals(
        Found(
            FoodImage(
                url = "https://images.example.org/food.jpg",
                author = "Jane Doe",
                sourceName = "example",
                sourceUrl = "https://example.org/photos/123/",
                title = "Soupe aux légumes",
                license = "cc0",
                licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
                licenseVersion = "1.0",
                authorUrl = "https://example.org/jane")),
        result)
    assertEquals("api.openverse.org", connection.requestedUrl?.host)
    assertEquals("https", connection.requestedUrl?.protocol)
    assertEquals("/v1/images/", connection.requestedUrl?.path)
    assertEquals("soupe à l'oignon & pain", connection.request().url.queryParameter("q"))
    assertEquals("cc0,by", connection.request().url.queryParameter("license"))
    assertEquals("20", connection.request().url.queryParameter("page_size"))
    assertEquals("true", connection.request().url.queryParameter("filter_dead"))
    assertNull(connection.request().header("Authorization"))
    assertNotSame(callingThread, connection.requestThread)
    assertTrue(connection.streamClosed)
    assertTrue(connection.bodyClosed)
  }

  @Test
  fun emptyResultsAreDistinctFromFailure() = runBlocking {
    val connection = SimulatedCall(body = """{"results": []}""")

    assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    assertTrue(connection.streamClosed)
    assertTrue(connection.bodyClosed)
  }

  @Test
  fun networkFailureReturnsAnExplicitFailureAndReleasesConnection() = runBlocking {
    for (error in listOf(IOException("Simulated network failure"), SocketTimeoutException())) {
      val connection = SimulatedCall(onRead = { throw error })

      assertEquals(Failure(Reason.NETWORK), repository(connection).searchByTitle("soupe"))
      assertTrue(connection.bodyClosed)
    }
  }

  @Test
  fun connectionOpeningFailureReturnsAnExplicitFailure() = runBlocking {
    val repository =
        OpenverseFoodImageRepository(Call.Factory { throw IOException("Simulated call failure") })

    assertEquals(Failure(Reason.NETWORK), repository.searchByTitle("soupe"))
  }

  @Test
  fun emptyAndBlankTitlesDoNotOpenAConnection() = runBlocking {
    for (title in listOf("", " \n\t ")) {
      val connection = SimulatedCall()

      assertEquals(Failure(Reason.EMPTY_TITLE), repository(connection).searchByTitle(title))
      assertNull(connection.requestedUrl)
    }
  }

  @Test
  fun publicConstructorNeedsNoKeyOrConfiguration() = runBlocking {
    val repository: FoodImageRepository = OpenverseFoodImageRepository()

    assertEquals(Failure(Reason.EMPTY_TITLE), repository.searchByTitle(""))
  }

  @Test
  fun httpErrorsKeepTheirStatusAndDoNotParseTheBody() = runBlocking {
    for (status in listOf(401, 429, 503)) {
      val connection = SimulatedCall(status = status, body = "not JSON")

      assertEquals(Failure(Reason.HTTP, status), repository(connection).searchByTitle("soupe"))
      assertFalse(connection.streamOpened)
      assertEquals(1, connection.openCount)
      assertTrue(connection.bodyClosed)
    }
  }

  @Test
  fun redirectsRemainExplicitHttpFailures() = runBlocking {
    val connection = SimulatedCall(status = 302)

    assertEquals(Failure(Reason.HTTP, 302), repository(connection).searchByTitle("soupe"))
    assertTrue(connection.bodyClosed)
  }

  @Test
  fun invalidResponsesAreNotReportedAsEmptyResults() = runBlocking {
    val invalidBodies =
        listOf(
            "not JSON",
            "{}",
            "[]",
            """{"results": null}""",
            """{"results": {}}""",
            """{"results": "not an array"}""")
    for (body in invalidBodies) {
      val connection = SimulatedCall(body = body)

      assertEquals(Failure(Reason.INVALID_RESPONSE), repository(connection).searchByTitle("soupe"))
      assertTrue(connection.streamClosed)
      assertTrue(connection.bodyClosed)
    }
  }

  @Test
  fun missingOptionalMetadataStaysNullWithoutInventingCredits() = runBlocking {
    val optionalFields = listOf("title", "creator", "creator_url", "source", "license_version")
    for (value in listOf(null, JSONObject.NULL, " \n ", 42)) {
      val photo = image()
      for (field in optionalFields) {
        if (value == null) photo.remove(field) else photo.put(field, value)
      }
      val connection = SimulatedCall(body = response(photo))

      val found = repository(connection).searchByTitle("Titre recherché") as Found

      assertNull(found.image.title)
      assertNull(found.image.author)
      assertNull(found.image.authorUrl)
      assertNull(found.image.sourceName)
      assertNull(found.image.licenseVersion)
      assertEquals("https://example.org/photos/123/", found.image.sourceUrl)
      assertEquals("cc0", found.image.license)
    }
  }

  @Test
  fun ccByMetadataIsPreservedEvenWhenTheAuthorIsUnavailable() = runBlocking {
    val photo =
        image()
            .put("license", "by")
            .put("license_version", "4.0")
            .put("license_url", "https://creativecommons.org/licenses/by/4.0/")
            .put("creator", JSONObject.NULL)
            .put("creator_url", "/relative-author")
    val connection = SimulatedCall(body = response(photo))

    val found = repository(connection).searchByTitle("soupe") as Found

    assertEquals("by", found.image.license)
    assertEquals("4.0", found.image.licenseVersion)
    assertEquals("https://creativecommons.org/licenses/by/4.0/", found.image.licenseUrl)
    assertNull(found.image.author)
    assertNull(found.image.authorUrl)
  }

  @Test
  fun selectsTheFirstUsableResultInApiOrder() = runBlocking {
    val firstUsable = image().put("title", "Première suggestion")
    val laterUsable = image().put("title", "Suggestion suivante")
    val invalidUrls =
        listOf(
            "",
            "/relative.jpg",
            "https:///missing-host.jpg",
            "javascript:alert(1)",
            "file:///food.jpg",
            "https://example.org/a b.jpg",
            "https://user:password@example.org/food.jpg",
            "https://example.org:99999/food.jpg")
    val entries =
        invalidUrls.map { image().put("url", it) } +
            listOf(
                JSONObject.NULL,
                "not an object",
                image().put("license", "by-nc"),
                firstUsable,
                laterUsable)
    val connection = SimulatedCall(body = response(*entries.toTypedArray()))

    val found = repository(connection).searchByTitle("soupe") as Found

    assertEquals("Première suggestion", found.image.title)
    assertEquals(1, connection.openCount)
  }

  @Test
  fun missingRequiredMetadataSkipsToTheNextUsableResult() = runBlocking {
    for (field in listOf("url", "foreign_landing_url", "license", "license_url")) {
      for (value in listOf(null, JSONObject.NULL, " ", 42)) {
        val incomplete = image()
        if (value == null) incomplete.remove(field) else incomplete.put(field, value)
        val complete = image().put("title", "Crédits exploitables")
        val connection = SimulatedCall(body = response(incomplete, complete))

        val found = repository(connection).searchByTitle("soupe") as Found

        assertEquals("Crédits exploitables", found.image.title)
      }
    }
  }

  @Test
  fun invalidAttributionLinksAreSkipped() = runBlocking {
    for (field in listOf("foreign_landing_url", "license_url")) {
      for (value in listOf("/relative", "https:///missing-host", "javascript:alert(1)")) {
        val connection = SimulatedCall(body = response(image().put(field, value)))

        assertSame(NotFound, repository(connection).searchByTitle("soupe"))
      }
    }
  }

  @Test
  fun licensesOtherThanCc0AndCcByAreRejectedEvenIfReturnedByTheApi() = runBlocking {
    for (license in listOf("by-sa", "by-nc", "by-nd", "by-nc-sa", "by-nc-nd", "pdm", "unknown")) {
      val connection = SimulatedCall(body = response(image().put("license", license)))

      assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    }
  }

  @Test
  fun noUsableResultsAreReportedAsNotFound() = runBlocking {
    val connection =
        SimulatedCall(
            body =
                response(
                    JSONObject(),
                    JSONObject.NULL,
                    image().put("url", "/relative.jpg"),
                    image().put("license_url", JSONObject.NULL)))

    assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    assertTrue(connection.streamClosed)
    assertTrue(connection.bodyClosed)
  }

  @Test
  fun absoluteHttpLinksRemainUnchanged() = runBlocking {
    val photo =
        image()
            .put("url", "http://images.example.org/food.jpg")
            .put("foreign_landing_url", "http://example.org/photos/123/")
    val connection = SimulatedCall(body = response(photo))

    val found = repository(connection).searchByTitle("soupe") as Found

    assertEquals("http://images.example.org/food.jpg", found.image.url)
    assertEquals("http://example.org/photos/123/", found.image.sourceUrl)
  }

  @Test
  fun cancellationIsPropagatedAndReleasesConnection() = runBlocking {
    val cancellation = CancellationException("Simulated cancellation")
    val connection = SimulatedCall(onRead = { throw cancellation })

    try {
      repository(connection).searchByTitle("soupe")
      fail("Cancellation must propagate")
    } catch (exception: CancellationException) {
      // La récupération des traces de coroutines peut copier l'exception.
      assertEquals(cancellation.message, exception.message)
    }
    assertTrue(connection.bodyClosed)
  }

  @Test
  fun cancellingAnInFlightRequestDoesNotBecomeANetworkFailure() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val releaseRead = CountDownLatch(1)
    val connection =
        SimulatedCall(
            onRead = {
              started.complete(Unit)
              check(releaseRead.await(5, TimeUnit.SECONDS)) { "Test did not release the response" }
              throw IOException("Simulated interrupted request")
            })
    val search = launch {
      repository(connection).searchByTitle("soupe")
      fail("Cancelled search must not return a result")
    }
    try {
      withTimeout(5_000) { started.await() }
      search.cancel()
    } finally {
      releaseRead.countDown()
    }
    withTimeout(5_000) { search.join() }

    assertTrue(search.isCancelled)
    assertTrue(connection.isCanceled())
    withTimeout(5_000) { connection.responseDelivered.await() }
    assertTrue(connection.bodyClosed)
  }

  private fun repository(connection: SimulatedCall): OpenverseFoodImageRepository =
      OpenverseFoodImageRepository(
          Call.Factory { request ->
            connection.originalRequest = request
            connection.requestedUrl = request.url.toUrl()
            connection.openCount++
            connection
          })

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun timeoutCancelsTheCallWithoutWaitingForCallbackAndClosesLateResponse() = runTest {
    val connection = SimulatedCall(deferResponse = true)
    val result = async { withTimeoutOrNull(5_000) { repository(connection).searchByTitle("Milk") } }
    runCurrent()
    assertFalse(result.isCompleted)
    advanceTimeBy(5_000)
    runCurrent()
    assertNull(result.await())
    assertTrue(connection.isCanceled())
    // A late success must still close its body and cannot replace the timeout result.
    connection.deliverResponse()
    assertTrue(connection.bodyClosed)
    assertFalse(connection.streamOpened)
  }

  @Test
  fun asynchronousNetworkFailureIsReported() = runBlocking {
    val connection = SimulatedCall(deferResponse = true)
    val result = async { repository(connection).searchByTitle("Milk") }
    kotlinx.coroutines.yield()
    connection.callback.onFailure(connection, IOException("Offline"))
    assertEquals(Failure(Reason.NETWORK), result.await())
  }

  @Test
  fun deadlineClosesRealSocketWhileResponseBodyIsStalled() = runBlocking {
    // Only loopback traffic: no Openverse or other external service is contacted.
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val headersSent = CompletableDeferred<Unit>()
    val peerClosed = CompletableDeferred<Boolean>()
    val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    var call: Call? = null
    val serverThread = Thread {
      try {
        server.accept().use { socket ->
          // Test watchdog must outlast the five-second lookup deadline.
          socket.soTimeout = 10_000
          val input = socket.getInputStream().bufferedReader()
          while (!input.readLine().isNullOrEmpty()) {
            /* Read HTTP request headers. */
          }
          socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
            flush()
          }
          // Leave the body stalled; cancellation must close the socket, not wait 30 seconds.
          headersSent.complete(Unit)
          peerClosed.complete(input.read() == -1)
        }
      } catch (exception: Exception) {
        headersSent.completeExceptionally(exception)
        peerClosed.completeExceptionally(exception)
      }
    }
    serverThread.start()
    try {
      val repository =
          OpenverseFoodImageRepository(
              Call.Factory { request ->
                client
                    .newCall(
                        request.newBuilder().url("http://127.0.0.1:${server.localPort}/").build())
                    .also { call = it }
              })
      val result = async { withTimeoutOrNull(5_000) { repository.searchByTitle("Milk") } }
      withTimeout(10_000) { headersSent.await() }
      assertNull(withTimeout(10_000) { result.await() })
      assertTrue(call!!.isCanceled())
      assertTrue(withTimeout(10_000) { peerClosed.await() })
    } finally {
      call?.cancel()
      server.close()
      serverThread.join(10_000)
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdownNow()
    }
  }

  private fun image(): JSONObject =
      JSONObject(SUCCESS_BODY).getJSONArray("results").getJSONObject(0)

  private fun response(vararg results: Any): String =
      JSONObject().put("results", JSONArray(results.toList())).toString()

  private class SimulatedCall(
      private val status: Int = 200,
      private val body: String = SUCCESS_BODY,
      private val deferResponse: Boolean = false,
      private val onRead: () -> Unit = {}
  ) : Call {
    val responseDelivered = CompletableDeferred<Unit>()
    var openCount = 0
    var requestedUrl: URL? = null
    var requestThread: Thread? = null
    @Volatile var bodyClosed = false
    var streamOpened = false
    var streamClosed = false
    lateinit var originalRequest: Request
    lateinit var callback: Callback
    @Volatile private var cancelled = false
    private var executed = false

    override fun request() = originalRequest

    override fun execute(): Response = error("Only asynchronous calls are supported")

    override fun enqueue(responseCallback: Callback) {
      executed = true
      callback = responseCallback
      if (!deferResponse) Thread { deliverResponse() }.start()
    }

    fun deliverResponse() {
      requestThread = Thread.currentThread()
      val source =
          object : ForwardingSource(Buffer().writeUtf8(body)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                  onRead()
                  streamOpened = true
                  return super.read(sink, byteCount)
                }

                override fun close() {
                  streamClosed = true
                  bodyClosed = true
                  super.close()
                }
              }
              .buffer()
      val responseBody =
          object : ResponseBody() {
            override fun contentType() = null

            override fun contentLength() = -1L

            override fun source(): BufferedSource = source
          }
      callback.onResponse(
          this,
          Response.Builder()
              .request(originalRequest)
              .protocol(Protocol.HTTP_1_1)
              .code(status)
              .message("Simulated response")
              .body(responseBody)
              .build())
      responseDelivered.complete(Unit)
    }

    override fun cancel() {
      cancelled = true
    }

    override fun isCanceled() = cancelled

    override fun isExecuted() = executed

    override fun timeout() = Timeout.NONE

    override fun clone(): Call =
        SimulatedCall(status, body, deferResponse, onRead).also {
          it.originalRequest = originalRequest
        }
  }

  companion object {
    private const val SUCCESS_BODY =
        """{
          "results": [{
            "url": "https://images.example.org/food.jpg",
            "title": "Soupe aux légumes",
            "creator": "Jane Doe",
            "creator_url": "https://example.org/jane",
            "source": "example",
            "foreign_landing_url": "https://example.org/photos/123/",
            "license": "cc0",
            "license_version": "1.0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/"
          }]
        }"""
  }
}
