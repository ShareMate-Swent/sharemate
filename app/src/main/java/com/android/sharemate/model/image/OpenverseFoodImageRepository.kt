// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Recherche anonyme Openverse limitée aux licences CC0 et CC BY. Le premier résultat exploitable
 * est une suggestion dont la pertinence n'est pas garantie. Aucun fichier image n'est téléchargé.
 */
class OpenverseFoodImageRepository
internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val connectionFactory: (URL) -> HttpURLConnection
) : FoodImageRepository {
  constructor() : this(Dispatchers.IO, { it.openConnection() as HttpURLConnection })

  override suspend fun searchByTitle(title: String): FoodImageSearchResult =
      withContext(ioDispatcher) {
        ensureActive()
        val query = title.trim()
        if (query.isEmpty()) return@withContext Failure(Reason.EMPTY_TITLE)

        val url =
            URL(
                "https://api.openverse.org/v1/images/?q=${URLEncoder.encode(query, "UTF-8")}" +
                    "&license=cc0,by&page_size=20&filter_dead=true")
        try {
          val connection = connectionFactory(url)
          try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            // Un changement d'endpoint doit rester un résultat HTTP explicite.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            ensureActive()
            if (status !in 200..299) {
              Failure(Reason.HTTP, status)
            } else {
              val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
              ensureActive()
              parseResponse(body)
            }
          } finally {
            connection.disconnect()
          }
        } catch (exception: IOException) {
          ensureActive()
          Failure(Reason.NETWORK)
        } catch (exception: JSONException) {
          ensureActive()
          Failure(Reason.INVALID_RESPONSE)
        }
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
