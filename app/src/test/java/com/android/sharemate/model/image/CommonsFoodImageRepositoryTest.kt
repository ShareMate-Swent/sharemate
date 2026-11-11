// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic HTTP tests: only the loopback server is contacted, never Commons. */
@RunWith(AndroidJUnit4::class)
class CommonsFoodImageRepositoryTest {
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient
  private lateinit var factory: Call.Factory
  private lateinit var repository: CommonsFoodImageRepository
  private val requests = mutableListOf<Request>()
  private val calls = mutableListOf<Call>()

  @Before
  fun setUp() {
    server = MockWebServer().also { it.start() }
    client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    factory =
        Call.Factory { request ->
          requests += request
          val local =
              request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()
          client.newCall(request.newBuilder().url(local).build()).also { calls += it }
        }
    repository = CommonsFoodImageRepository(factory)
  }

  @After
  fun tearDown() {
    calls.forEach(Call::cancel)
    server.shutdown()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun lookupDeadlineCancelsTheCallAndReturnsNoImage() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val shortBudget = CommonsFoodImageRepository(factory, lookupTimeoutMs = 500)

    assertSame(NotFound, shortBudget.searchByTitle("milk"))
    assertEquals(1, requests.size)
    assertTrue(calls.single().isCanceled())
  }

  @Test
  fun automaticLookupExpandsTheActualHttpRequestsToTenTwentyAndThirtyCandidates() = runBlocking {
    server.enqueue(MockResponse().setBody(response()))
    server.enqueue(MockResponse().setBody(response(page(title = "Chicken soup"))))
    server.enqueue(MockResponse().setBody(response(page(title = "Raw chicken"))))

    val selected =
        FoodImageSelector(repository).selectAutomatic("chicken") as FoodImageSelection.RemoteImage

    assertEquals("Raw chicken", selected.image.title)
    assertEquals(listOf("10", "20", "30"), requests.map { it.url.queryParameter("gsrlimit") })
  }

  @Test
  fun concurrentLookupsKeepTheirOwnCandidateLimits() = runBlocking {
    repeat(2) { server.enqueue(MockResponse().setBody(response(page()))) }
    val first = async { repository.searchByTitle("tacos", 20) }
    val second = async { repository.searchByTitle("tacos", 30) }

    assertTrue(first.await() is Found)
    assertTrue(second.await() is Found)
    assertEquals(setOf("20", "30"), requests.map { it.url.queryParameter("gsrlimit") }.toSet())
  }

  @Test
  fun nonPositiveCandidateLimitsAreRejectedWithoutNetworkCalls() {
    for (limit in listOf(0, -1)) {
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.searchByTitle("milk", limit) }
      }
    }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun returnsThumbnailAndDecodesAttributionWithoutLosingLinks() = runBlocking {
    server.enqueue(MockResponse().setBody(response(page())))

    assertEquals(
        Found(
            FoodImage(
                url = "https://upload.wikimedia.org/thumb/tacos.jpg",
                author = "Jane & John",
                sourceName = "Wikimedia Commons",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:Tacos.jpg",
                title = "Fresh tacos & rice",
                license = "by",
                licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                licenseVersion = "4.0",
                authorUrl = "https://commons.wikimedia.org/wiki/User:Jane")),
        repository.searchByTitle("  tacos  "))
    val request = requests.single()
    assertEquals("commons.wikimedia.org", request.url.host)
    assertEquals("https", request.url.scheme)
    assertEquals("/w/api.php", request.url.encodedPath)
    assertEquals("query", request.url.queryParameter("action"))
    assertEquals("2", request.url.queryParameter("formatversion"))
    assertEquals("search", request.url.queryParameter("generator"))
    assertEquals("6", request.url.queryParameter("gsrnamespace"))
    assertEquals("10", request.url.queryParameter("gsrlimit"))
    assertEquals("640", request.url.queryParameter("iiurlwidth"))
    assertEquals("\"tacos\" filetype:bitmap", request.url.queryParameter("gsrsearch"))
    assertEquals("application/json", request.header("Accept"))
    assertTrue(request.header("User-Agent")!!.contains("ShareMate-Swent/sharemate"))
    assertNull(request.header("Authorization"))
  }

  @Test
  fun titleIsEncodedAsALiteralAndCannotAddSearchOperators() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"batchcomplete":true}"""))
    repository.searchByTitle("  onion soup & bread \" OR \\  ")

    assertEquals(
        "\"onion soup & bread OR\" filetype:bitmap",
        requests.single().url.queryParameter("gsrsearch"))
  }

  @Test
  fun emptyTitlesAndPublicConstructorNeedNoNetworkOrApiKey() = runBlocking {
    for (title in listOf("", " \n\t ", "\"\\")) {
      assertEquals(Failure(Reason.EMPTY_TITLE), repository.searchByTitle(title))
    }
    assertTrue(requests.isEmpty())
    assertEquals(Failure(Reason.EMPTY_TITLE), CommonsFoodImageRepository().searchByTitle(""))
  }

  @Test
  fun usesGeneratorRankingInsteadOfJsonArrayOrder() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                response(
                    page(index = 5, title = "Tacos later"),
                    page(index = 1, title = "Tacos first"))))

    assertEquals("Tacos first", (repository.searchByTitle("tacos") as Found).image.title)
  }

  @Test
  fun prefersTheIngredientButKeepsAnExplicitlyRequestedDish() = runBlocking {
    for ((query, expected) in
        listOf("chicken" to "Raw chicken", "chicken soup" to "Chicken soup")) {
      server.enqueue(
          MockResponse()
              .setBody(
                  response(
                      page(index = 1, title = "Chicken soup"),
                      page(index = 2, title = "Grilled chicken"),
                      page(index = 3, title = "Raw chicken"))))

      assertEquals(expected, (repository.searchByTitle(query) as Found).image.title)
    }
  }

  @Test
  fun prefersTheExactTitleOverAdditionalIngredientDescriptions() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                response(
                    page(index = 1, title = "Fresh chicken meat"),
                    page(index = 2, title = "Chicken meat"),
                    page(index = 3, title = "Chicken"))))

    assertEquals("Chicken", (repository.searchByTitle("chicken") as Found).image.title)
  }

  @Test
  fun normalizesCaseAndPunctuationWithoutChangingTheAttribution() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                response(
                    page(index = 1, title = "Chicken meat salad"),
                    page(index = 2, title = "Chicken—MEAT"))))

    assertEquals("Chicken—MEAT", (repository.searchByTitle(" CHICKEN meat ") as Found).image.title)

    val fallback = page().put("title", "File:CHICKEN_MEAT.JPG")
    metadata(fallback).remove("ObjectName")
    server.enqueue(MockResponse().setBody(response(fallback)))

    assertEquals(
        "CHICKEN_MEAT.JPG", (repository.searchByTitle("chicken meat") as Found).image.title)
  }

  @Test
  fun unrequestedDishesPartialWordsAndWeakMatchesKeepTheGenericImage() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                response(
                    page(title = "Chicken soup"),
                    page(title = "Chicken salad"),
                    page(title = "Chickens"),
                    page(title = "Chicken served with rice and vegetables"),
                    page(title = "Rice"))))

    assertSame(NotFound, repository.searchByTitle("chicken"))
  }

  @Test
  fun theWholeRequestedPhraseMustMatch() = runBlocking {
    server.enqueue(MockResponse().setBody(response(page(title = "Raw chicken"))))

    assertSame(NotFound, repository.searchByTitle("chicken soup"))
  }

  @Test
  fun aCloserTitleWithAnInvalidLicenseDoesNotHideAUsableImage() = runBlocking {
    val invalid = page(index = 1, title = "Chicken")
    metadata(invalid).put("LicenseShortName", value("GFDL"))
    server.enqueue(
        MockResponse().setBody(response(invalid, page(index = 2, title = "Chicken meat"))))

    assertEquals("Chicken meat", (repository.searchByTitle("chicken") as Found).image.title)
  }

  @Test
  fun cc0AndShareAlikeKeepTheCorrectLicenseAndVersion() = runBlocking {
    for ((name, id, path, version) in
        listOf(
            listOf("CC0", "cc0", "publicdomain/zero/1.0", "1.0"),
            listOf("CC BY-SA 3.0", "by-sa", "licenses/by-sa/3.0", "3.0"))) {
      val item = page()
      metadata(item)
          .put("LicenseShortName", value(name))
          .put("LicenseUrl", value("http://creativecommons.org/$path/"))
      server.enqueue(MockResponse().setBody(response(item)))

      val image = (repository.searchByTitle("tacos") as Found).image
      assertEquals(id, image.license)
      assertEquals(version, image.licenseVersion)
      assertEquals("https://creativecommons.org/$path/", image.licenseUrl)
    }
  }

  @Test
  fun unsupportedOrConflictingLicenseMetadataIsSkipped() = runBlocking {
    for ((name, url) in
        listOf(
            "CC BY-NC 4.0" to "https://creativecommons.org/licenses/by-nc/4.0/",
            "Public domain" to "https://example.org/license",
            "GFDL" to "https://example.org/license",
            "CC BY 4.0" to "https://creativecommons.org/licenses/by-sa/4.0/",
            "CC BY 4.0" to "https://example.org/licenses/by/4.0/",
            "CC BY 4.0" to "https://user:password@creativecommons.org/licenses/by/4.0/")) {
      val item = page()
      metadata(item).put("LicenseShortName", value(name)).put("LicenseUrl", value(url))
      server.enqueue(MockResponse().setBody(response(item)))

      assertSame(NotFound, repository.searchByTitle("tacos"))
    }
  }

  @Test
  fun malformedEntriesAndUnsupportedFilesDoNotHideALaterUsablePhoto() = runBlocking {
    val missing = page().put("missing", true)
    val svg = page().also { info(it).put("mime", "image/svg+xml") }
    val noThumbnail = page().also { info(it).remove("thumburl") }
    val uncredited = page().also { metadata(it).remove("Artist") }
    val cleartext = page().also { info(it).put("thumburl", "http://example.org/tacos.jpg") }
    val invalidSource = page().also { info(it).put("descriptionurl", "/relative") }
    server.enqueue(
        MockResponse()
            .setBody(
                response(
                    JSONObject.NULL,
                    "invalid",
                    missing,
                    svg,
                    noThumbnail,
                    uncredited,
                    cleartext,
                    invalidSource,
                    page(title = "Usable tacos"))))

    assertEquals("Usable tacos", (repository.searchByTitle("tacos") as Found).image.title)
  }

  @Test
  fun aDecodableThumbnailOfATiffIsUsableWithoutDownloadingTheOriginal() = runBlocking {
    val item = page().also { info(it).put("mime", "image/tiff").put("thumbmime", "image/jpeg") }
    server.enqueue(MockResponse().setBody(response(item)))

    assertEquals(
        "https://upload.wikimedia.org/thumb/tacos.jpg",
        (repository.searchByTitle("tacos") as Found).image.url)
  }

  @Test
  fun requiredFieldsMustBeNonBlankStrings() = runBlocking {
    for (field in listOf("thumburl", "descriptionurl")) {
      for (invalid in
          listOf(
              JSONObject.NULL,
              42,
              " ",
              "javascript:alert(1)",
              "https://user:secret@example.org/image")) {
        val item = page().also { info(it).put(field, invalid) }
        server.enqueue(MockResponse().setBody(response(item)))

        assertSame(NotFound, repository.searchByTitle("tacos"))
      }
    }
  }

  @Test
  fun optionalFieldsDoNotInventCreditsAndCc0NeedsNoArtist() = runBlocking {
    val item = page()
    metadata(item)
        .put("LicenseShortName", value("CC0"))
        .put("LicenseUrl", value("https://creativecommons.org/publicdomain/zero/1.0/"))
        .remove("Artist")
    metadata(item).remove("ObjectName")
    server.enqueue(MockResponse().setBody(response(item)))

    val image = (repository.searchByTitle("tacos") as Found).image
    assertNull(image.author)
    assertNull(image.authorUrl)
    assertEquals("Tacos.jpg", image.title)
  }

  @Test
  fun emptySearchIsDifferentFromMalformedDataOrMediaWikiErrors() = runBlocking {
    for (body in listOf("""{"batchcomplete":true}""", response())) {
      server.enqueue(MockResponse().setBody(body))
      assertSame(NotFound, repository.searchByTitle("unknown food"))
    }
    for (body in
        listOf(
            "{}",
            "not json",
            "[]",
            """{"query":{"pages":{}}}""",
            """{"error":{"code":"cirrussearch-too-busy-error"}}""")) {
      server.enqueue(MockResponse().setBody(body))
      assertEquals(Failure(Reason.INVALID_RESPONSE), repository.searchByTitle("tacos"))
    }
  }

  @Test
  fun httpFailuresAndRedirectsKeepTheirStatus() = runBlocking {
    for (status in listOf(302, 403, 429, 503, 504)) {
      server.enqueue(MockResponse().setResponseCode(status).setBody("not JSON"))

      assertEquals(Failure(Reason.HTTP, status), repository.searchByTitle("tacos"))
    }
    assertEquals(5, server.requestCount)
  }

  @Test
  fun networkFailureAndCallCreationFailureAreExplicit() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    assertEquals(Failure(Reason.NETWORK), repository.searchByTitle("tacos"))
    val broken = CommonsFoodImageRepository(Call.Factory { throw java.io.IOException("Offline") })
    assertEquals(Failure(Reason.NETWORK), broken.searchByTitle("tacos"))
  }

  @Test
  fun cancellationAbortsTheActualHttpCallWithoutReturningAFallback() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val search = async { repository.searchByTitle("tacos") }
    val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
    assertNotNull(request)

    search.cancelAndJoin()

    assertTrue(search.isCancelled)
    assertTrue(calls.single().isCanceled())
  }

  @Test
  fun anOuterDeadlineAlsoCancelsAStalledBody() = runBlocking {
    server.enqueue(MockResponse().setBody(" ".repeat(10000)).setBodyDelay(2, TimeUnit.SECONDS))
    val search = async { withTimeoutOrNull(1000) { repository.searchByTitle("tacos") } }
    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }

    assertNull(search.await())
    assertTrue(calls.single().isCanceled())
  }

  private fun info(page: JSONObject) = page.getJSONArray("imageinfo").getJSONObject(0)

  private fun metadata(page: JSONObject) = info(page).getJSONObject("extmetadata")

  private fun value(text: String) = JSONObject().put("value", text)

  private fun page(index: Int = 1, title: String = "Fresh tacos &amp; rice"): JSONObject =
      JSONObject()
          .put("title", "File:Tacos.jpg")
          .put("index", index)
          .put(
              "imageinfo",
              JSONArray()
                  .put(
                      JSONObject()
                          .put("url", "https://upload.wikimedia.org/original/tacos.jpg")
                          .put("thumburl", "https://upload.wikimedia.org/thumb/tacos.jpg")
                          .put("mime", "image/jpeg")
                          .put(
                              "descriptionurl", "https://commons.wikimedia.org/wiki/File:Tacos.jpg")
                          .put(
                              "extmetadata",
                              JSONObject()
                                  .put(
                                      "Artist",
                                      value(
                                          "<a href=\"//commons.wikimedia.org/wiki/User:Jane\">Jane &amp; John</a>"))
                                  .put("ObjectName", value(title))
                                  .put("LicenseShortName", value("CC BY 4.0"))
                                  .put(
                                      "LicenseUrl",
                                      value("https://creativecommons.org/licenses/by/4.0/")))))

  private fun response(vararg pages: Any): String =
      JSONObject()
          .put("batchcomplete", true)
          .put("query", JSONObject().put("pages", JSONArray(pages.toList())))
          .toString()
}
