// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.product

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.product.ProductLookupResult.Failure
import com.android.sharemate.model.product.ProductLookupResult.Found
import com.android.sharemate.model.product.ProductLookupResult.NotFound
import com.android.sharemate.model.product.ProductLookupResult.Reason
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenFoodFactsProductCatalogRepositoryTest {
  private val server = MockWebServer()
  private lateinit var repository: OpenFoodFactsProductCatalogRepository

  @Before
  fun setUp() {
    server.start()
    repository = OpenFoodFactsProductCatalogRepository(OkHttpClient(), server.url("/"))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun mapsProductNameAndBrandAndRequestsOnlyNeededFields() = runBlocking {
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"status":1,"product":{"product_name":"  Oat milk  ","brands":"Oat Co"}}"""))

    assertEquals(
        Found(ProductDetails("1234567890123", "Oat milk", "Oat Co")),
        repository.lookupByBarcode(" 1234567890123 "))
    val request = server.takeRequest()
    assertEquals("/api/v2/product/1234567890123?fields=product_name%2Cbrands", request.path)
    assertEquals("application/json", request.getHeader("Accept"))
    assertEquals("ShareMate/1.0 (Android)", request.getHeader("User-Agent"))
  }

  @Test
  fun invalidBarcodesDoNotMakeARequest() = runBlocking {
    listOf(" \t ", "abc", "12", "1234567", "123456789012345", "1234567a", "1234 5678")
        .forEach { barcode ->
          assertEquals(Failure(Reason.INVALID_BARCODE), repository.lookupByBarcode(barcode))
        }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun acceptsEightAndFourteenDigitBarcodesAndPreservesLeadingZeroes() = runBlocking {
    val barcodes = listOf("00000000", "00000000000000")
    barcodes.forEach { barcode ->
      server.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Rice"}}"""))

      assertEquals(
          Found(ProductDetails(barcode, "Rice", null)),
          repository.lookupByBarcode(barcode))
      assertEquals("/api/v2/product/$barcode?fields=product_name%2Cbrands", server.takeRequest().path)
    }
  }

  @Test
  fun catalogMissIsDistinctFromMalformedResponse() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"status":0,"status_verbose":"product not found"}"""))

    assertEquals(NotFound, repository.lookupByBarcode("12345678"))
  }

  @Test
  fun preservesAvailableProductFieldsWhenOneIsMissing() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Rice"}}"""))

    assertEquals(
        Found(ProductDetails("12345678", "Rice", null)), repository.lookupByBarcode("12345678"))
  }

  @Test
  fun httpErrorsKeepTheirStatus() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(503).setBody("not JSON"))

    assertEquals(Failure(Reason.HTTP, 503), repository.lookupByBarcode("12345678"))
  }

  @Test
  fun networkErrorsAreReportedExplicitly() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

    assertEquals(Failure(Reason.NETWORK), repository.lookupByBarcode("12345678"))
  }

  @Test
  fun malformedResponsesDoNotBecomeCatalogMisses() = runBlocking {
    val malformedBodies =
        listOf(
            "not JSON",
            "{}",
            """{"status":"1","product":{}}""",
            """{"status":1}""",
            """{"status":1,"product":{"product_name":42}}""",
            """{"status":2}""")

    malformedBodies.forEach { body ->
      server.enqueue(MockResponse().setBody(body))
      assertEquals(Failure(Reason.INVALID_RESPONSE), repository.lookupByBarcode("12345678"))
    }
  }
}
