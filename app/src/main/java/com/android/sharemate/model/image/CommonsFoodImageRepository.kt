// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import com.android.sharemate.model.HttpHeaders
import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_CALL_TIMEOUT_MS
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_CONNECT_TIMEOUT_MS
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_READ_TIMEOUT_MS
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_INITIAL_CANDIDATES
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_TIMEOUT_MS
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Anonymous Commons search for English ingredient names, with CC0, CC BY and CC BY-SA attribution.
 * Searches the supplied literal phrase and ranks usable images by title similarity.
 */
class CommonsFoodImageRepository
internal constructor(
    private val callFactory: Call.Factory,
    private val lookupTimeoutMs: Long = IMAGE_LOOKUP_TIMEOUT_MS
) : FoodImageRepository {
  constructor() : this(client)

  override suspend fun searchByTitle(title: String): FoodImageSearchResult =
      searchByTitle(title, IMAGE_LOOKUP_INITIAL_CANDIDATES)

  override suspend fun searchByTitle(title: String, candidateLimit: Int): FoodImageSearchResult {
    require(candidateLimit > 0) { "Candidate limit must be positive" }
    currentCoroutineContext().ensureActive()
    // Keep user input inside a quoted literal, rather than accepting CirrusSearch operators.
    val query = title.replace(Regex("[\"\\\\\\s]+"), " ").trim()
    if (query.isEmpty()) return Failure(Reason.EMPTY_TITLE)
    val url =
        API_ENDPOINT.toHttpUrl()
            .newBuilder()
            .addQueryParameter(ACTION, QUERY)
            .addQueryParameter(FORMAT, JSON_FORMAT)
            .addQueryParameter(FORMAT_VERSION, API_FORMAT_VERSION)
            .addQueryParameter(GENERATOR, SEARCH_GENERATOR)
            .addQueryParameter(SEARCH_TERM, "\"$query\" $BITMAP_SEARCH_FILTER")
            .addQueryParameter(SEARCH_NAMESPACE, FILE_NAMESPACE)
            .addQueryParameter(SEARCH_LIMIT, candidateLimit.toString())
            .addQueryParameter(PROPERTY, IMAGE_INFO)
            .addQueryParameter(
                IMAGE_INFO_PROPERTIES, "url|$MIME|$THUMBNAIL_MIME|$EXTENDED_METADATA")
            .addQueryParameter(THUMBNAIL_WIDTH, THUMBNAIL_WIDTH_PX.toString())
            .addQueryParameter(METADATA_FILTER, "$ARTIST|$OBJECT_NAME|$LICENSE_NAME|$LICENSE_URL")
            .build()
    val request =
        Request.Builder()
            .url(url)
            .header(HttpHeaders.ACCEPT, HttpHeaders.JSON_MEDIA_TYPE)
            .header(HttpHeaders.USER_AGENT, FoodImageHttp.USER_AGENT)
            .build()
    return withTimeoutOrNull(lookupTimeoutMs) {
      try {
        awaitResult(callFactory.newCall(request), query)
      } catch (exception: IOException) {
        currentCoroutineContext().ensureActive()
        Failure(Reason.NETWORK)
      } catch (exception: JSONException) {
        Failure(Reason.INVALID_RESPONSE)
      }
    } ?: NotFound
  }

  private suspend fun awaitResult(call: Call, query: String): FoodImageSearchResult =
      suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
              }

              override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                  response.use {
                    continuation.context.ensureActive()
                    if (!it.isSuccessful) Failure(Reason.HTTP, it.code)
                    else parseResponse(it.body?.string().orEmpty(), query)
                  }
                }
                continuation.resumeWith(result)
              }
            })
      }

  private fun parseResponse(body: String, query: String): FoodImageSearchResult {
    val root = JSONObject(body)
    // MediaWiki can report errors (including overload) inside an HTTP 200 response.
    if (root.has(ERROR)) return Failure(Reason.INVALID_RESPONSE)
    if (!root.has(QUERY) && root.optBoolean(BATCH_COMPLETE)) return NotFound
    val pages = root.getJSONObject(QUERY).getJSONArray(PAGES)
    val queryWords = titleWords(query)
    var bestImage: FoodImage? = null
    var bestScore = 0.0
    // Keep Commons relevance as the tie-breaker, independently of JSON array order.
    val ranked =
        (0 until pages.length()).mapNotNull(pages::optJSONObject).sortedBy {
          it.optInt(INDEX, Int.MAX_VALUE)
        }
    for (page in ranked) {
      if (page.has(MISSING)) continue
      val info = page.optJSONArray(IMAGE_INFO)?.optJSONObject(0) ?: continue
      val mime = info.string(THUMBNAIL_MIME) ?: info.string(MIME)
      if (mime !in THUMBNAIL_MIME_TYPES) continue
      // Never fetch the potentially very large original, nor an Android-blocked cleartext URL.
      val thumbnail = httpsUrl(info.string(THUMBNAIL_URL)) ?: continue
      val source = httpsUrl(info.string(DESCRIPTION_URL)) ?: continue
      val metadata = info.optJSONObject(EXTENDED_METADATA) ?: continue
      val license = parseLicense(metadata) ?: continue
      val artist =
          metadata.value(ARTIST)?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY) }
      val author = artist?.toString()?.trim()?.takeIf(String::isNotBlank)
      if (license.id != FoodImageLicenseIds.CC0 && author == null) continue
      val authorUrl =
          artist?.getSpans(0, artist.length, URLSpan::class.java)?.firstNotNullOfOrNull { span ->
            httpsUrl(if (span.url.startsWith("//")) "https:${span.url}" else span.url)
          }
      val imageTitle =
          metadata
              .value(OBJECT_NAME)
              ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
              ?.takeIf(String::isNotBlank) ?: page.string(TITLE)?.removePrefix(FILE_TITLE_PREFIX)
      val score = titleRelevance(imageTitle, queryWords)
      if (score <= bestScore) continue
      bestScore = score
      bestImage =
          FoodImage(
              url = thumbnail,
              author = author,
              sourceName = SOURCE_NAME,
              sourceUrl = source,
              title = imageTitle,
              license = license.id,
              licenseUrl = license.url,
              licenseVersion = license.version,
              authorUrl = authorUrl)
    }
    return bestImage?.let(::Found) ?: NotFound
  }

  private fun titleRelevance(title: String?, queryWords: List<String>): Double {
    val words = titleWords(title.orEmpty())
    if (queryWords.isEmpty() || !words.windowed(queryWords.size).contains(queryWords)) return 0.0
    val extraWords = words.filterNot(queryWords::contains)
    // A containing dish is unsuitable unless the user explicitly requested that dish.
    if (extraWords.any(PREPARED_DISH_WORDS::contains)) return 0.0
    if (queryWords.size.toDouble() / words.size < MIN_TITLE_COVERAGE) return 0.0
    val extraWordCost =
        extraWords.sumOf { if (it in INGREDIENT_WORDS) INGREDIENT_WORD_COST else 1.0 }
    return queryWords.size / (queryWords.size + extraWordCost)
  }

  private fun titleWords(title: String): List<String> =
      title
          .lowercase(Locale.ROOT)
          .replace(Regex("\\.(jpe?g|png|webp|tiff?)$"), "")
          .split(Regex("[^\\p{L}\\p{N}]+"))
          .filter(String::isNotBlank)

  private data class License(val id: String, val version: String, val url: String)

  private fun parseLicense(metadata: JSONObject): License? {
    val name = metadata.value(LICENSE_NAME) ?: return null
    val match = VERSIONED_LICENSE_NAME.matchEntire(name)
    val id =
        if (name == CC0_LICENSE_NAME) FoodImageLicenseIds.CC0
        else match?.groupValues?.get(1)?.lowercase() ?: return null
    val version = if (id == FoodImageLicenseIds.CC0) CC0_LICENSE_VERSION else match!!.groupValues[2]
    val url = metadata.value(LICENSE_URL)?.toHttpUrlOrNull() ?: return null
    if (url.host !in LICENSE_HOSTS ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.port !in LICENSE_PORTS) {
      return null
    }
    val expectedPath =
        if (id == FoodImageLicenseIds.CC0) CC0_LICENSE_PATH else "/licenses/$id/$version"
    if (url.encodedPath.trimEnd('/') != expectedPath) return null
    // Commons still supplies some HTTP license links; upgrade only the known CC origin.
    return License(
        id, version, url.newBuilder().scheme(HTTPS_SCHEME).port(HTTPS_PORT).build().toString())
  }

  private fun JSONObject.string(name: String): String? =
      (opt(name) as? String)?.trim()?.takeIf(String::isNotBlank)

  private fun JSONObject.value(name: String): String? = optJSONObject(name)?.string(METADATA_VALUE)

  private fun httpsUrl(value: String?): String? {
    val url = value?.toHttpUrlOrNull() ?: return null
    return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
  }

  private companion object {
    const val API_ENDPOINT = "https://commons.wikimedia.org/w/api.php"
    const val ACTION = "action"
    const val QUERY = "query"
    const val FORMAT = "format"
    const val JSON_FORMAT = "json"
    const val FORMAT_VERSION = "formatversion"
    const val API_FORMAT_VERSION = "2"
    const val GENERATOR = "generator"
    const val SEARCH_GENERATOR = "search"
    const val SEARCH_NAMESPACE = "gsrnamespace"
    const val FILE_NAMESPACE = "6"
    const val SEARCH_LIMIT = "gsrlimit"
    const val PROPERTY = "prop"
    const val IMAGE_INFO = "imageinfo"
    const val IMAGE_INFO_PROPERTIES = "iiprop"
    const val METADATA_FILTER = "iiextmetadatafilter"
    const val ERROR = "error"
    const val BATCH_COMPLETE = "batchcomplete"
    const val PAGES = "pages"
    const val INDEX = "index"
    const val MISSING = "missing"
    const val THUMBNAIL_MIME = "thumbmime"
    const val MIME = "mime"
    const val THUMBNAIL_URL = "thumburl"
    const val DESCRIPTION_URL = "descriptionurl"
    const val EXTENDED_METADATA = "extmetadata"
    const val ARTIST = "Artist"
    const val OBJECT_NAME = "ObjectName"
    const val TITLE = "title"
    const val FILE_TITLE_PREFIX = "File:"
    const val SOURCE_NAME = "Wikimedia Commons"
    const val LICENSE_NAME = "LicenseShortName"
    const val LICENSE_URL = "LicenseUrl"
    const val METADATA_VALUE = "value"
    const val CC0_LICENSE_NAME = "CC0"
    const val CC0_LICENSE_VERSION = "1.0"
    const val CC0_LICENSE_PATH = "/publicdomain/zero/1.0"
    const val SEARCH_TERM = "gsrsearch"
    const val BITMAP_SEARCH_FILTER = "filetype:bitmap"
    const val THUMBNAIL_WIDTH = "iiurlwidth"
    const val THUMBNAIL_WIDTH_PX = 640
    const val INGREDIENT_WORD_COST = 0.25
    const val HTTPS_SCHEME = "https"
    const val HTTP_PORT = 80
    const val HTTPS_PORT = 443
    val LICENSE_PORTS = setOf(HTTP_PORT, HTTPS_PORT)
    val LICENSE_HOSTS = setOf("creativecommons.org", "www.creativecommons.org")
    val VERSIONED_LICENSE_NAME = Regex("CC (BY|BY-SA) (1\\.0|2\\.0|2\\.5|3\\.0|4\\.0)")
    val THUMBNAIL_MIME_TYPES = setOf(PhotoMimeTypes.JPEG, PhotoMimeTypes.PNG, PhotoMimeTypes.WEBP)
    const val MIN_TITLE_COVERAGE = 1.0 / 3
    val INGREDIENT_WORDS = setOf("raw", "fresh", "meat")
    val PREPARED_DISH_WORDS = setOf("soup", "salad", "pizza", "sandwich", "curry", "stew", "recipe")
    val client =
        OkHttpClient.Builder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
  }
}
