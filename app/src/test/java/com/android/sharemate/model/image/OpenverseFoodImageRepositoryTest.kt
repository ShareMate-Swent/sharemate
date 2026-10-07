// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    val connection = SimulatedConnection()
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
    assertEquals(
        "q=soupe+%C3%A0+l%27oignon+%26+pain&license=cc0,by&page_size=20&filter_dead=true",
        connection.requestedUrl?.query)
    assertNull(connection.getRequestProperty("Authorization"))
    assertNotSame(callingThread, connection.requestThread)
    assertTrue(connection.streamClosed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun emptyResultsAreDistinctFromFailure() = runBlocking {
    val connection = SimulatedConnection(body = """{"results": []}""")

    assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    assertTrue(connection.streamClosed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun networkFailureReturnsAnExplicitFailureAndReleasesConnection() = runBlocking {
    for (error in listOf(IOException("Simulated network failure"), SocketTimeoutException())) {
      val connection = SimulatedConnection(onRead = { throw error })

      assertEquals(Failure(Reason.NETWORK), repository(connection).searchByTitle("soupe"))
      assertTrue(connection.disconnected)
    }
  }

  @Test
  fun connectionOpeningFailureReturnsAnExplicitFailure() = runBlocking {
    val repository =
        OpenverseFoodImageRepository(Dispatchers.IO) {
          throw IOException("Simulated connection failure")
        }

    assertEquals(Failure(Reason.NETWORK), repository.searchByTitle("soupe"))
  }

  @Test
  fun emptyAndBlankTitlesDoNotOpenAConnection() = runBlocking {
    for (title in listOf("", " \n\t ")) {
      val connection = SimulatedConnection()

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
      val connection = SimulatedConnection(status = status, body = "not JSON")

      assertEquals(Failure(Reason.HTTP, status), repository(connection).searchByTitle("soupe"))
      assertFalse(connection.streamOpened)
      assertEquals(1, connection.openCount)
      assertTrue(connection.disconnected)
    }
  }

  @Test
  fun redirectsRemainExplicitHttpFailures() = runBlocking {
    val connection = SimulatedConnection(status = 302)

    assertEquals(Failure(Reason.HTTP, 302), repository(connection).searchByTitle("soupe"))
    assertFalse(connection.instanceFollowRedirects)
    assertTrue(connection.disconnected)
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
      val connection = SimulatedConnection(body = body)

      assertEquals(Failure(Reason.INVALID_RESPONSE), repository(connection).searchByTitle("soupe"))
      assertTrue(connection.streamClosed)
      assertTrue(connection.disconnected)
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
      val connection = SimulatedConnection(body = response(photo))

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
    val connection = SimulatedConnection(body = response(photo))

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
    val connection = SimulatedConnection(body = response(*entries.toTypedArray()))

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
        val connection = SimulatedConnection(body = response(incomplete, complete))

        val found = repository(connection).searchByTitle("soupe") as Found

        assertEquals("Crédits exploitables", found.image.title)
      }
    }
  }

  @Test
  fun invalidAttributionLinksAreSkipped() = runBlocking {
    for (field in listOf("foreign_landing_url", "license_url")) {
      for (value in listOf("/relative", "https:///missing-host", "javascript:alert(1)")) {
        val connection = SimulatedConnection(body = response(image().put(field, value)))

        assertSame(NotFound, repository(connection).searchByTitle("soupe"))
      }
    }
  }

  @Test
  fun licensesOtherThanCc0AndCcByAreRejectedEvenIfReturnedByTheApi() = runBlocking {
    for (license in listOf("by-sa", "by-nc", "by-nd", "by-nc-sa", "by-nc-nd", "pdm", "unknown")) {
      val connection = SimulatedConnection(body = response(image().put("license", license)))

      assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    }
  }

  @Test
  fun noUsableResultsAreReportedAsNotFound() = runBlocking {
    val connection =
        SimulatedConnection(
            body =
                response(
                    JSONObject(),
                    JSONObject.NULL,
                    image().put("url", "/relative.jpg"),
                    image().put("license_url", JSONObject.NULL)))

    assertSame(NotFound, repository(connection).searchByTitle("soupe"))
    assertTrue(connection.streamClosed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun absoluteHttpLinksRemainUnchanged() = runBlocking {
    val photo =
        image()
            .put("url", "http://images.example.org/food.jpg")
            .put("foreign_landing_url", "http://example.org/photos/123/")
    val connection = SimulatedConnection(body = response(photo))

    val found = repository(connection).searchByTitle("soupe") as Found

    assertEquals("http://images.example.org/food.jpg", found.image.url)
    assertEquals("http://example.org/photos/123/", found.image.sourceUrl)
  }

  @Test
  fun cancellationIsPropagatedAndReleasesConnection() = runBlocking {
    val cancellation = CancellationException("Simulated cancellation")
    val connection = SimulatedConnection(onRead = { throw cancellation })

    try {
      repository(connection).searchByTitle("soupe")
      fail("Cancellation must propagate")
    } catch (exception: CancellationException) {
      // La récupération des traces de coroutines peut copier l'exception.
      assertEquals(cancellation.message, exception.message)
    }
    assertTrue(connection.disconnected)
  }

  @Test
  fun cancellingAnInFlightRequestDoesNotBecomeANetworkFailure() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val releaseRead = CountDownLatch(1)
    val connection =
        SimulatedConnection(
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
    assertTrue(connection.disconnected)
  }

  private fun repository(connection: SimulatedConnection): OpenverseFoodImageRepository =
      OpenverseFoodImageRepository(Dispatchers.IO) { url ->
        connection.requestedUrl = url
        connection.requestThread = Thread.currentThread()
        connection.openCount++
        connection
      }

  private fun image(): JSONObject =
      JSONObject(SUCCESS_BODY).getJSONArray("results").getJSONObject(0)

  private fun response(vararg results: Any): String =
      JSONObject().put("results", JSONArray(results.toList())).toString()

  private class SimulatedConnection(
      private val status: Int = 200,
      body: String = SUCCESS_BODY,
      private val onRead: () -> Unit = {}
  ) : HttpURLConnection(URL("https://api.openverse.org/v1/images/")) {
    var openCount = 0
    var requestedUrl: URL? = null
    var requestThread: Thread? = null
    var disconnected = false
    var streamOpened = false
    var streamClosed = false
    private val responseStream =
        object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
          override fun close() {
            streamClosed = true
            super.close()
          }
        }

    override fun getResponseCode(): Int = status

    override fun getInputStream(): InputStream {
      onRead()
      streamOpened = true
      return responseStream
    }

    override fun connect() {}

    override fun disconnect() {
      disconnected = true
    }

    override fun usingProxy(): Boolean = false
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
