// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

import com.android.sharemate.model.HttpHeaders
import com.android.sharemate.model.product.ProductLookupResult.Failure
import com.android.sharemate.model.product.ProductLookupResult.Found
import com.android.sharemate.model.product.ProductLookupResult.NotFound
import com.android.sharemate.model.product.ProductLookupResult.Reason
import java.io.IOException
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

class OpenFoodFactsProductCatalogRepository
internal constructor(
    private val callFactory: Call.Factory,
    private val baseUrl: HttpUrl,
) : ProductCatalogRepository {
  constructor() : this(client, openFoodFactsUrl)

  override suspend fun lookupByBarcode(barcode: String): ProductLookupResult {
    currentCoroutineContext().ensureActive()
    val normalizedBarcode = barcode.trim()
    if (normalizedBarcode.length !in SUPPORTED_BARCODE_LENGTHS ||
        normalizedBarcode.any { it !in '0'..'9' }) {
      return Failure(Reason.INVALID_BARCODE)
    }

    val url =
        baseUrl
            .newBuilder()
            .addPathSegment(API_SEGMENT)
            .addPathSegment(VERSION_SEGMENT)
            .addPathSegment(PRODUCT)
            .addPathSegment(normalizedBarcode)
            .addQueryParameter(FIELDS, "$PRODUCT_NAME,$BRANDS")
            .build()
    val request =
        Request.Builder()
            .url(url)
            .header(HttpHeaders.ACCEPT, HttpHeaders.JSON_MEDIA_TYPE)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .build()

    return try {
      awaitResult(callFactory.newCall(request), normalizedBarcode)
    } catch (exception: IOException) {
      currentCoroutineContext().ensureActive()
      Failure(Reason.NETWORK)
    } catch (exception: JSONException) {
      Failure(Reason.INVALID_RESPONSE)
    }
  }

  private suspend fun awaitResult(call: Call, barcode: String): ProductLookupResult =
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
                    if (it.code == HTTP_NOT_FOUND) {
                      val parsed =
                          runCatching { parseResponse(it.body?.string().orEmpty(), barcode) }
                              .getOrNull()
                      if (parsed == NotFound) NotFound else Failure(Reason.HTTP, it.code)
                    } else if (!it.isSuccessful) Failure(Reason.HTTP, it.code)
                    else parseResponse(it.body?.string().orEmpty(), barcode)
                  }
                }
                continuation.resumeWith(result)
              }
            })
      }

  private fun parseResponse(body: String, barcode: String): ProductLookupResult {
    val response = JSONObject(body)
    val status = response.get(STATUS) as? Number ?: throw JSONException("Missing product status")
    val integerStatus =
        status.toLong().takeIf { status.toDouble() == it.toDouble() }
            ?: throw JSONException("Invalid product status")
    return when (integerStatus) {
      PRODUCT_NOT_FOUND -> NotFound
      PRODUCT_FOUND -> {
        val product =
            response.get(PRODUCT) as? JSONObject ?: throw JSONException("Missing product details")
        Found(
            ProductDetails(
                barcode = barcode,
                name = product.optionalString(PRODUCT_NAME),
                brand = product.optionalString(BRANDS)))
      }
      else -> throw JSONException("Unknown product status")
    }
  }

  private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = get(name) as? String ?: throw JSONException("Invalid product field: $name")
    return value.trim().takeIf { it.isNotEmpty() }
  }

  private companion object {
    const val API_SEGMENT = "api"
    const val VERSION_SEGMENT = "v2"
    const val PRODUCT = "product"
    const val STATUS = "status"
    const val FIELDS = "fields"
    const val PRODUCT_NAME = "product_name"
    const val BRANDS = "brands"
    const val USER_AGENT = "ShareMate/1.0 (Android)"
    const val TIMEOUT_SECONDS = 20L
    const val PRODUCT_NOT_FOUND = 0L
    const val PRODUCT_FOUND = 1L
    val SUPPORTED_BARCODE_LENGTHS = 8..14
    val openFoodFactsUrl = "https://world.openfoodfacts.org/".toHttpUrl()

    val client =
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
  }
}
