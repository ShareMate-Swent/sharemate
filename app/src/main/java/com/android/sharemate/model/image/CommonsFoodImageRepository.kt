// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_CALL_TIMEOUT_MS
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_CONNECT_TIMEOUT_MS
import com.android.sharemate.model.image.FoodImageTimeouts.HTTP_READ_TIMEOUT_MS
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * Anonymous Commons search with CC0, CC BY and CC BY-SA attribution. Searches file titles in a food
 * context to reduce unrelated matches (for example English "pain"). Relevance is not guaranteed.
 */
class CommonsFoodImageRepository internal constructor(private val callFactory: Call.Factory) :
    FoodImageRepository {
  constructor() : this(client)

  override suspend fun searchByTitle(title: String): FoodImageSearchResult {
    currentCoroutineContext().ensureActive()
    // Keep user input inside a quoted literal, rather than accepting CirrusSearch operators.
    val query = title.replace(Regex("[\"\\\\\\s]+"), " ").trim()
    if (query.isEmpty()) return Failure(Reason.EMPTY_TITLE)
    val url =
        "https://commons.wikimedia.org/w/api.php"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "intitle:\"$query\" $FOOD_CONTEXT filetype:bitmap")
            .addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrlimit", "10")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|mime|thumbmime|extmetadata")
            .addQueryParameter("iiurlwidth", "640")
            .addQueryParameter(
                "iiextmetadatafilter", "Artist|ObjectName|LicenseShortName|LicenseUrl")
            .build()
    val request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", FoodImageHttp.USER_AGENT)
            .build()
    return try {
      awaitResult(callFactory.newCall(request))
    } catch (exception: IOException) {
      currentCoroutineContext().ensureActive()
      Failure(Reason.NETWORK)
    } catch (exception: JSONException) {
      Failure(Reason.INVALID_RESPONSE)
    }
  }

  private suspend fun awaitResult(call: Call): FoodImageSearchResult =
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
                    else parseResponse(it.body?.string().orEmpty())
                  }
                }
                continuation.resumeWith(result)
              }
            })
      }

  private fun parseResponse(body: String): FoodImageSearchResult {
    val root = JSONObject(body)
    // MediaWiki can report errors (including overload) inside an HTTP 200 response.
    if (root.has("error")) return Failure(Reason.INVALID_RESPONSE)
    if (!root.has("query") && root.optBoolean("batchcomplete")) return NotFound
    val pages = root.getJSONObject("query").getJSONArray("pages")
    // The array order is not the relevance order; the generator supplies a separate index.
    val ranked =
        (0 until pages.length()).mapNotNull(pages::optJSONObject).sortedBy {
          it.optInt("index", Int.MAX_VALUE)
        }
    for (page in ranked) {
      if (page.has("missing")) continue
      val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      val mime = info.string("thumbmime") ?: info.string("mime")
      if (mime !in setOf("image/jpeg", "image/png", "image/webp")) continue
      // Never fetch the potentially very large original, nor an Android-blocked cleartext URL.
      val thumbnail = httpsUrl(info.string("thumburl")) ?: continue
      val source = httpsUrl(info.string("descriptionurl")) ?: continue
      val metadata = info.optJSONObject("extmetadata") ?: continue
      val license = parseLicense(metadata) ?: continue
      val artist =
          metadata.value("Artist")?.let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY)
          }
      val author = artist?.toString()?.trim()?.takeIf(String::isNotBlank)
      if (license.id != "cc0" && author == null) continue
      val authorUrl =
          artist?.getSpans(0, artist.length, URLSpan::class.java)?.firstNotNullOfOrNull { span ->
            httpsUrl(if (span.url.startsWith("//")) "https:${span.url}" else span.url)
          }
      val imageTitle =
          metadata
              .value("ObjectName")
              ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
              ?.takeIf(String::isNotBlank) ?: page.string("title")?.removePrefix("File:")
      return Found(
          FoodImage(
              url = thumbnail,
              author = author,
              sourceName = "Wikimedia Commons",
              sourceUrl = source,
              title = imageTitle,
              license = license.id,
              licenseUrl = license.url,
              licenseVersion = license.version,
              authorUrl = authorUrl))
    }
    return NotFound
  }

  private data class License(val id: String, val version: String, val url: String)

  private fun parseLicense(metadata: JSONObject): License? {
    val name = metadata.value("LicenseShortName") ?: return null
    val match = Regex("CC (BY|BY-SA) (1\\.0|2\\.0|2\\.5|3\\.0|4\\.0)").matchEntire(name)
    val id = if (name == "CC0") "cc0" else match?.groupValues?.get(1)?.lowercase() ?: return null
    val version = if (id == "cc0") "1.0" else match!!.groupValues[2]
    val url = metadata.value("LicenseUrl")?.toHttpUrlOrNull() ?: return null
    if (url.host !in setOf("creativecommons.org", "www.creativecommons.org") ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.port !in setOf(80, 443)) {
      return null
    }
    val expectedPath = if (id == "cc0") "/publicdomain/zero/1.0" else "/licenses/$id/$version"
    if (url.encodedPath.trimEnd('/') != expectedPath) return null
    // Commons still supplies some HTTP license links; upgrade only the known CC origin.
    return License(id, version, url.newBuilder().scheme("https").port(443).build().toString())
  }

  private fun JSONObject.string(name: String): String? =
      (opt(name) as? String)?.trim()?.takeIf(String::isNotBlank)

  private fun JSONObject.value(name: String): String? = optJSONObject(name)?.string("value")

  private fun httpsUrl(value: String?): String? {
    val url = value?.toHttpUrlOrNull() ?: return null
    return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
  }

  private companion object {
    const val FOOD_CONTEXT =
        "(food OR dish OR fruit OR vegetable OR bread OR rice OR soup OR cuisine)"
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
