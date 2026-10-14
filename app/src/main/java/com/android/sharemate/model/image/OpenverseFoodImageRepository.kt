// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Recherche anonyme Openverse limitée aux licences CC0 et CC BY. Le premier résultat exploitable
 * est une suggestion dont la pertinence n'est pas garantie. Aucun fichier image n'est téléchargé.
 */
class OpenverseFoodImageRepository internal constructor(private val callFactory: Call.Factory) :
    FoodImageRepository {
  constructor() : this(client)

  override suspend fun searchByTitle(title: String): FoodImageSearchResult {
    currentCoroutineContext().ensureActive()
    val query = title.trim()
    if (query.isEmpty()) return Failure(Reason.EMPTY_TITLE)
    val url =
        "https://api.openverse.org/v1/images/"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("license", "cc0,by")
            .addQueryParameter("page_size", "20")
            .addQueryParameter("filter_dead", "true")
            .build()
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
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
        // Cancellation releases the caller promptly and aborts the underlying network request.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
              }

              override fun onResponse(call: Call, response: Response) {
                // Consume/close the body on OkHttp's worker, never on the UI thread.
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

  private companion object {
    val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
  }

  private fun parseResponse(body: String): FoodImageSearchResult {
    val results = JSONObject(body).getJSONArray("results")
    for (index in 0 until results.length()) {
      val result = results.optJSONObject(index) ?: continue
      val imageUrl = result.httpUrl("url") ?: continue
      val license = result.optionalString("license")?.lowercase(Locale.ROOT)
      if (license != "cc0" && license != "by") continue
      // Sans ces liens, les crédits ne sont pas exploitables. Essayer le résultat suivant.
      val sourceUrl = result.httpUrl("foreign_landing_url") ?: continue
      val licenseUrl = result.httpUrl("license_url") ?: continue
      return Found(
          FoodImage(
              url = imageUrl,
              author = result.optionalString("creator"),
              sourceName = result.optionalString("source"),
              sourceUrl = sourceUrl,
              title = result.optionalString("title"),
              license = license,
              licenseUrl = licenseUrl,
              licenseVersion = result.optionalString("license_version"),
              authorUrl = result.httpUrl("creator_url")))
    }
    return NotFound
  }

  private fun JSONObject.optionalString(name: String): String? =
      (opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

  /** Validation syntaxique uniquement, sans requête supplémentaire vers le site de l'image. */
  private fun JSONObject.httpUrl(name: String): String? {
    val value = optionalString(name) ?: return null
    val uri =
        try {
          URI(value)
        } catch (exception: URISyntaxException) {
          return null
        }
    return value.takeIf {
      (uri.scheme.equals("https", ignoreCase = true) ||
          uri.scheme.equals("http", ignoreCase = true)) &&
          !uri.host.isNullOrBlank() &&
          uri.rawUserInfo == null &&
          (uri.port == -1 || uri.port in 1..65535)
    }
  }
}
