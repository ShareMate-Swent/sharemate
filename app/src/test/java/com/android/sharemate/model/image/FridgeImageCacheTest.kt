// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FridgeImageCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  private val context
    get() = RuntimeEnvironment.getApplication()

  private val online = MutableStateFlow(true)
  private var currentUser = "alice"
  private val remote = "https://images.example.org/milk.png?size=120"
  private val photo = PersonalPhotoReference("itemPhotos/milk/00000000-0000-0000-0000-000000000001")
  private val disk by lazy { FridgeImageCache.diskCache(temporary.root) }
  private var responseBody: () -> ResponseBody = { error("Unexpected HTTP request") }
  private val requestedUrls = mutableListOf<String>()
  private val client =
      OkHttpClient.Builder()
          .addInterceptor { chain ->
            assertTrue(online.value)
            requestedUrls += chain.request().url.toString()
            assertEquals(FoodImageHttp.USER_AGENT, chain.request().header("User-Agent"))
            val headers =
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Cache-Control", "no-cache")
                    .build()
            headers.newBuilder().networkResponse(headers).body(responseBody()).build()
          }
          .build()

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun invalidHttpSuccessDoesNotPreventTheNextLoad() = runBlocking {
    val session = cache().session("alice", "home")
    responseBody = { byteArrayOf(1, 2, 3).toResponseBody("image/png".toMediaType()) }
    assertTrue(session.load(remote) is ErrorResult)
    assertEquals(0L, disk.size)

    serve(Color.BLUE)
    assertEquals(Color.BLUE, session.load(remote).bitmap().getPixel(0, 0))
    assertEquals(listOf(remote, remote), requestedUrls)
  }

  @Test
  fun largeRemoteImageIsStoredAsABoundedThumbnail() = runBlocking {
    val source =
        png(dimension = 1536) {
          val random = Random(123)
          IntArray(width * height) { Color.BLACK or random.nextInt(0x1000000) }
              .let { setPixels(it, 0, width, 0, 0, width, height) }
        }
    assertTrue(source.size > PersonalPhotoRepository.MAX_BYTES)
    responseBody = { source.toResponseBody("image/png".toMediaType()) }

    val viewed = cache().session("alice", "home").load(remote).bitmap()
    assertTrue(viewed.width <= FridgeImageCache.THUMBNAIL_PIXELS)
    online.value = false

    val offline = cache().session("alice", "home").load(remote) as SuccessResult
    assertEquals(DataSource.DISK, offline.dataSource)
    assertEquals(viewed.width, offline.bitmap().width)
    assertEquals(viewed.height, offline.bitmap().height)
    assertEquals(1, requestedUrls.size)
    assertEquals(50L * 1024 * 1024, disk.maxSize)
    assertTrue(disk.size in 1 until source.size.toLong())
  }

  @Test
  fun unchangedUrlUsesMemoryAndDiskWhileANewReferenceDownloadsItsOwnImage() = runBlocking {
    val session = cache().session("alice", "home")
    serve(Color.RED)
    assertEquals(Color.RED, session.load(remote).bitmap().getPixel(0, 0))
    assertEquals(DataSource.MEMORY_CACHE, (session.load(remote) as SuccessResult).dataSource)

    serve(Color.BLUE)
    online.value = false
    val reopened = cache().session("alice", "home")
    val fromDisk = reopened.load(remote) as SuccessResult
    assertEquals(DataSource.DISK, fromDisk.dataSource)
    assertEquals(Color.RED, fromDisk.bitmap().getPixel(0, 0))

    online.value = true
    val changed = remote.replace("120", "240")
    val fromNetwork = reopened.load(changed) as SuccessResult
    assertEquals(DataSource.NETWORK, fromNetwork.dataSource)
    assertEquals(Color.BLUE, fromNetwork.bitmap().getPixel(0, 0))
    assertEquals(Color.RED, reopened.load(remote).bitmap().getPixel(0, 0))
    assertEquals(listOf(remote, changed), requestedUrls)
  }

  @Test
  fun cacheEntriesAreScopedToAccountHouseholdAndCompleteReference() = runBlocking {
    val cache = cache()
    val alice = cache.session("alice", "home")
    assertTrue(alice.load(photo) { png { eraseColor(Color.RED) } } is SuccessResult)
    serve(Color.BLUE)
    assertTrue(alice.load(remote) is SuccessResult)

    online.value = false
    assertTrue(cache().session("alice", "home").load(photo) is SuccessResult)
    assertOffline(cache.session("alice", "other-home").load(photo))
    assertOffline(cache(bucket = "other-bucket").session("alice", "home").load(photo))
    assertOffline(alice.load(remote.replace("120", "240")))
    currentUser = "bob"
    assertOffline(cache.session("bob", "home").load(remote))
    assertTrue(alice.load(photo) is ErrorResult)
  }

  @Test
  fun purgeClearsTheDedicatedFridgeCacheAndInvalidatesTheSession() = runBlocking {
    val cache = cache()
    repeat(3) { event ->
      online.value = true
      val session = cache.session("alice", "home")
      serve(Color.BLUE)
      assertTrue(session.load(remote) is SuccessResult)
      assertTrue(session.load(photo) { png { eraseColor(Color.RED) } } is SuccessResult)
      when (event) {
        0 -> cache.purgeAccount("alice")
        1 -> cache.purgeHousehold("alice", "home")
        2 -> session.revoke()
      }
      assertFalse(session.isActive.value)
      assertEquals(0L, disk.size)
      assertTrue(session.imageLoader.memoryCache!!.keys.isEmpty())
      online.value = false
      val reopened = cache.session("alice", "home")
      assertOffline(reopened.load(remote))
      assertOffline(reopened.load(photo))
    }
  }

  @Test
  fun downloadCompletingAfterRevocationCannotPopulateEitherCache() = runBlocking {
    val cache = cache()
    val session = cache.session("alice", "home")
    val started = CompletableDeferred<Unit>()
    val allowCompletion = CompletableDeferred<Unit>()
    val bytes = png { eraseColor(Color.RED) }
    val request = async {
      session.load(photo) {
        started.complete(Unit)
        allowCompletion.await()
        bytes
      }
    }
    withTimeout(5_000) { started.await() }
    session.revoke()
    allowCompletion.complete(Unit)

    assertTrue(request.await() is ErrorResult)
    assertEquals(0L, disk.size)
    assertTrue(session.imageLoader.memoryCache!!.keys.isEmpty())
    online.value = false
    assertOffline(cache.session("alice", "home").load(photo))
  }

  private fun cache(bucket: String = "bucket") =
      FridgeImageCache(context, disk, bucket, { it == currentUser }, { online.value }, client)

  private suspend fun FridgeImageCacheSession.load(url: String) =
      load(fridgeThumbnailRequest(context, 80).data(url))

  private suspend fun FridgeImageCacheSession.load(
      reference: PersonalPhotoReference,
      download: suspend (PersonalPhotoReference) -> ByteArray = {
        error("Unexpected Storage download")
      },
  ) = load(fridgeThumbnailRequest(context, 80).firebasePhoto(reference, download))

  private suspend fun FridgeImageCacheSession.load(builder: ImageRequest.Builder) =
      withTimeout(10_000) { imageLoader.execute(withCache(builder.build())) }

  private fun assertOffline(result: ImageResult) {
    assertTrue(result is ErrorResult)
  }

  private fun ImageResult.bitmap() = ((this as SuccessResult).drawable as BitmapDrawable).bitmap

  private fun serve(color: Int) {
    val bytes = png { eraseColor(color) }
    responseBody = { bytes.toResponseBody("image/png".toMediaType()) }
  }

  private fun png(dimension: Int = 16, fill: Bitmap.() -> Unit): ByteArray {
    val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.fill()
      ByteArrayOutputStream()
          .apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }
          .toByteArray()
    } finally {
      bitmap.recycle()
    }
  }
}
