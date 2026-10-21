// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

import com.android.sharemate.model.product.ProductLookupResult.Failure
import com.android.sharemate.model.product.ProductLookupResult.Found
import com.android.sharemate.model.product.ProductLookupResult.NotFound
import com.android.sharemate.model.product.ProductLookupResult.Reason
import java.io.IOException
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
    if (normalizedBarcode.isEmpty()) return Failure(Reason.INVALID_BARCODE)

    val url =
        baseUrl
            .newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("product")
            .addPathSegment(normalizedBarcode)
            .addQueryParameter("fields", "product_name,brands")
            .build()
    val request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ShareMate/1.0 (Android)")
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
                    if (!it.isSuccessful) Failure(Reason.HTTP, it.code)
                    else parseResponse(it.body?.string().orEmpty(), barcode)
                  }
                }
                continuation.resumeWith(result)
              }
            })
      }

  private fun parseResponse(body: String, barcode: String): ProductLookupResult {
    val response = JSONObject(body)
    val status = response.get("status") as? Number ?: throw JSONException("Missing product status")
    val integerStatus =
        status.toLong().takeIf { status.toDouble() == it.toDouble() }
            ?: throw JSONException("Invalid product status")
    return when (integerStatus) {
      0L -> NotFound
      1L -> {
        val product =
            response.get("product") as? JSONObject ?: throw JSONException("Missing product details")
        Found(
            ProductDetails(
                barcode = barcode,
                name = product.optionalString("product_name"),
                brand = product.optionalString("brands")))
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
    val openFoodFactsUrl = "https://world.openfoodfacts.org/".toHttpUrl()

    val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
  }
}
