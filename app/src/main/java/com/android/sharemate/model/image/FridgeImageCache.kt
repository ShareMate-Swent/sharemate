// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okio.Buffer

sealed interface FridgeImageReference

internal data class RemoteFridgeImageReference(val url: String) : FridgeImageReference

internal class FridgeImageOfflineException : IOException("Fridge image is unavailable offline")

/** Image requests and access lifecycle for one authorized fridge session. */
interface FridgeImageCacheSession {
  val isActive: StateFlow<Boolean>
  val online: StateFlow<Boolean>
  val imageLoader: ImageLoader

  fun request(
      context: Context,
      reference: FridgeImageReference,
      downloadPhoto: suspend (PersonalPhotoReference) -> ByteArray,
  ): ImageRequest

  suspend fun revoke()
}

/** Coil owns the bounded cache for this screen. Successful decodes are saved as thumbnails. */
@OptIn(ExperimentalCoilApi::class)
internal class FridgeImageCache(
    context: Context,
    private val diskCache: DiskCache,
    private val storageBucket: String,
    private val isCurrentUser: (String) -> Boolean,
    private val online: StateFlow<Boolean>,
    private val now: () -> Long = System::currentTimeMillis,
    callFactory: Call.Factory? = null,
) {
  private val sessions = mutableMapOf<Scope, Session>()
  private val loader =
      ImageLoader.Builder(context)
          .diskCache(diskCache)
          // Use the same connectivity state for both Firebase and HTTP requests.
          .networkObserverEnabled(false)
          .apply { if (callFactory != null) callFactory(callFactory) }
          .components { add(Interceptor(::intercept)) }
          .build()

  fun session(userId: String, householdId: String): FridgeImageCacheSession =
      synchronized(sessions) {
        val scope = Scope(userId, householdId)
        sessions.getOrPut(scope) { Session(scope) }
      }

  suspend fun purgeAccount(userId: String) = purge { it.userId == userId }

  suspend fun purgeHousehold(userId: String, householdId: String) = purge {
    it == Scope(userId, householdId)
  }

  private suspend fun purge(session: Session? = null, matches: (Scope) -> Boolean) =
      withContext(NonCancellable + Dispatchers.IO) {
        synchronized(sessions) {
          if (session?.isActive?.value == false) return@synchronized
          val revoked = sessions.filterKeys(matches)
          revoked.values.forEach { it.active.value = false }
          sessions.keys.removeAll(revoked.keys)
          loader.memoryCache?.clear()
          // Coil does not enumerate disk keys. The app has one active fridge, so purge this
          // loader's entire disk cache without keeping a second index of private images.
          diskCache.clear()
        }
      }

  private inner class Session(val scope: Scope) : FridgeImageCacheSession {
    val active = MutableStateFlow(true)
    override val isActive = active.asStateFlow()
    override val online
      get() = this@FridgeImageCache.online

    override val imageLoader
      get() = loader

    val generation = UUID.randomUUID().toString()

    fun hasAccess() = active.value && isCurrentUser(scope.userId)

    override fun request(
        context: Context,
        reference: FridgeImageReference,
        downloadPhoto: suspend (PersonalPhotoReference) -> ByteArray,
    ): ImageRequest =
        fridgeThumbnailRequest(context, THUMBNAIL_PIXELS)
            .data(Thumbnail(this, reference, downloadPhoto))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

    override suspend fun revoke() = purge(this) { it == scope }
  }

  private suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val data = chain.request.data as? Thumbnail ?: return chain.proceed(chain.request)
    val session = data.session
    val diskKey = data.diskKey()
    val memoryKey = MemoryCache.Key("${session.generation}:$diskKey")
    val request = chain.request.newBuilder().memoryCacheKey(memoryKey).build()
    try {
      if (!session.hasAccess()) return denied(request)
      val result = load(chain, request, data, diskKey, memoryKey)
      return if (session.hasAccess()) result else denied(request)
    } finally {
      // Coil may finish a memory write during or after a purge.
      if (!session.hasAccess()) loader.memoryCache?.remove(memoryKey)
    }
  }

  private suspend fun load(
      chain: Interceptor.Chain,
      request: ImageRequest,
      data: Thumbnail,
      diskKey: String,
      memoryKey: MemoryCache.Key,
  ): ImageResult {
    val isRemote = data.reference is RemoteFridgeImageReference
    val storedAt =
        if (isRemote) {
          loader.memoryCache?.get(memoryKey)?.extras?.get(FETCHED_AT) as? Long
              ?: withContext(Dispatchers.IO) { cachedAt(diskKey) }
        } else null
    val refresh = online.value && storedAt != null && now() - storedAt >= WEB_MAX_AGE_MILLIS
    if (!refresh) {
      val local = readThumbnail(chain, request, diskKey)
      if (local is SuccessResult) {
        if (isRemote && storedAt != null && local.dataSource == DataSource.DISK) {
          markMemoryFreshness(data.session, memoryKey, storedAt)
        }
        return local
      }
    }

    if (!online.value) return ErrorResult(null, request, FridgeImageOfflineException())
    if (!data.session.hasAccess()) return denied(request)
    val network = request.newBuilder().memoryCachePolicy(CachePolicy.WRITE_ONLY)
    when (val reference = data.reference) {
      is PersonalPhotoReference -> network.firebasePhoto(reference, data.downloadPhoto)
      is RemoteFridgeImageReference -> network.data(reference.url)
    }
    val result = chain.proceed(network.build())
    if (result is SuccessResult) {
      val fetchedAt = now()
      withContext(Dispatchers.IO) {
        saveThumbnail(data.session, diskKey, result.drawable as? BitmapDrawable, fetchedAt)
      }
      if (isRemote) markMemoryFreshness(data.session, memoryKey, fetchedAt)
      return result
    }

    // Keep a stale thumbnail usable if the server is unavailable or sends invalid image data.
    if (refresh) {
      val local = readThumbnail(chain, request, diskKey)
      if (local is SuccessResult) {
        if (isRemote && storedAt != null && local.dataSource == DataSource.DISK) {
          markMemoryFreshness(data.session, memoryKey, storedAt)
        }
        return local
      }
    }
    return if (online.value) result else ErrorResult(null, request, FridgeImageOfflineException())
  }

  private suspend fun readThumbnail(
      chain: Interceptor.Chain,
      request: ImageRequest,
      key: String,
  ): ImageResult {
    val cachedRequest =
        request
            .newBuilder()
            .data(DiskThumbnail(key))
            .fetcherFactory<DiskThumbnail> { data, options, _ ->
              Fetcher {
                val snapshot = diskCache.openSnapshot(data.key) ?: throw ThumbnailNotCached()
                SourceResult(
                    ImageSource(snapshot.data, diskCache.fileSystem, closeable = snapshot),
                    "image/png",
                    DataSource.DISK)
              }
            }
            .build()
    return chain.proceed(cachedRequest).also { result ->
      if (result is ErrorResult && result.throwable !is ThumbnailNotCached) {
        withContext(Dispatchers.IO) { diskCache.remove(key) }
      }
    }
  }

  private fun cachedAt(key: String): Long? =
      try {
        diskCache.openSnapshot(key)?.use { diskCache.fileSystem.read(it.metadata) { readLong() } }
      } catch (_: IOException) {
        null
      }

  private fun saveThumbnail(
      session: Session,
      key: String,
      image: BitmapDrawable?,
      fetchedAt: Long
  ) {
    if (image == null || !session.hasAccess()) return
    val thumbnail = Buffer()
    if (!image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, thumbnail.outputStream())) return
    // An editor must not commit after purge. Encoding and downloads happen outside this guard.
    synchronized(sessions) {
      if (!session.hasAccess()) return
      val editor =
          try {
            diskCache.openEditor(key)
          } catch (_: IOException) {
            null
          } ?: return
      try {
        diskCache.fileSystem.write(editor.metadata) { writeLong(fetchedAt) }
        diskCache.fileSystem.write(editor.data) { writeAll(thumbnail) }
        editor.commit()
      } catch (_: IOException) {
        editor.abort()
        // An unavailable cache should still allow displaying a valid downloaded image.
      }
    }
  }

  private fun markMemoryFreshness(session: Session, key: MemoryCache.Key, fetchedAt: Long) =
      synchronized(sessions) {
        if (!session.hasAccess()) return@synchronized
        val cache = loader.memoryCache ?: return@synchronized
        val value = cache[key] ?: return@synchronized
        cache[key] = value.copy(extras = value.extras + (FETCHED_AT to fetchedAt))
      }

  private fun denied(request: ImageRequest) =
      ErrorResult(null, request, IOException("Fridge image session is no longer active"))

  private fun Thumbnail.diskKey(): String {
    val source =
        when (val image = reference) {
          is PersonalPhotoReference -> "firebase:$storageBucket/${image.path}"
          is RemoteFridgeImageReference -> "web:${image.url}"
        }
    return "${session.scope.userId}\u0000${session.scope.householdId}\u0000$source"
  }

  private data class Scope(val userId: String, val householdId: String)

  private data class Thumbnail(
      val session: Session,
      val reference: FridgeImageReference,
      val downloadPhoto: suspend (PersonalPhotoReference) -> ByteArray,
  )

  private data class DiskThumbnail(val key: String)

  private class ThumbnailNotCached : IOException()

  companion object {
    fun diskCache(directory: File): DiskCache =
        DiskCache.Builder().directory(directory).maxSizeBytes(DISK_CACHE_BYTES).build()

    private const val DISK_CACHE_BYTES = 50L * 1024 * 1024
    // A single rendition stays available if the layout width changes while offline.
    internal const val THUMBNAIL_PIXELS = 512
    internal const val WEB_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    private const val FETCHED_AT = "fridge:fetched-at"
  }
}
