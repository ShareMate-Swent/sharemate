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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

/** Image requests and access lifecycle for one authorized fridge session. */
interface FridgeImageCacheSession {
  /** Whether this session is still active. */
  val isActive: StateFlow<Boolean>

  /** Loads image requests prepared for this session. */
  val imageLoader: ImageLoader

  /** Adds session-scoped caching to remote image requests. */
  fun withCache(request: ImageRequest): ImageRequest

  /** Ends this session and clears the entire fridge disk cache. */
  suspend fun revoke()
}

/** Coil owns the bounded cache for this screen. Successful decodes are saved as thumbnails. */
@OptIn(ExperimentalCoilApi::class)
internal class FridgeImageCache(
    context: Context,
    private val diskCache: DiskCache,
    private val storageBucket: String,
    private val isCurrentUser: (String) -> Boolean,
    callFactory: Call.Factory? = null,
) {
  private val sessions = mutableMapOf<Scope, Session>()
  private val loader =
      ImageLoader.Builder(context)
          .diskCache(diskCache)
          .networkObserverEnabled(false)
          .apply { if (callFactory != null) callFactory(callFactory) }
          .components {
            add(Interceptor(::intercept))
            add(
                Fetcher.Factory<DiskThumbnail> { thumbnail, _, _ ->
                  Fetcher {
                    val snapshot =
                        diskCache.openSnapshot(thumbnail.key)
                            ?: throw IOException("Thumbnail missing")
                    SourceResult(
                        ImageSource(snapshot.data, diskCache.fileSystem, closeable = snapshot),
                        "image/png",
                        DataSource.DISK)
                  }
                })
          }
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
    override val imageLoader
      get() = loader

    val generation = UUID.randomUUID().toString()

    fun hasAccess() = active.value && isCurrentUser(scope.userId)

    override fun withCache(request: ImageRequest): ImageRequest {
      val data = request.data
      val source =
          when (data) {
            is PersonalPhotoReference -> "firebase:$storageBucket/${data.path}"
            is String -> {
              if (data.toHttpUrlOrNull() == null) return request
              "web:$data"
            }
            else -> return request
          }
      val diskKey = "${scope.userId}\u0000${scope.householdId}\u0000$source"
      return request
          .newBuilder()
          .data(Thumbnail(this, diskKey, data))
          .size(THUMBNAIL_PIXELS, THUMBNAIL_PIXELS)
          .scale(Scale.FIT)
          .precision(Precision.INEXACT)
          .allowHardware(false)
          .diskCachePolicy(CachePolicy.DISABLED)
          .build()
    }

    override suspend fun revoke() = purge(this) { it == scope }
  }

  private suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val data = chain.request.data as? Thumbnail ?: return chain.proceed(chain.request)
    val session = data.session
    val memoryKey = MemoryCache.Key("${session.generation}:${data.diskKey}")
    val request = chain.request.newBuilder().memoryCacheKey(memoryKey).build()
    if (!session.hasAccess()) return denied(request)
    try {
      val result = load(chain, request, data)
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
  ): ImageResult {
    val cachedRequest = request.newBuilder().data(DiskThumbnail(data.diskKey)).build()
    val local = chain.proceed(cachedRequest)
    if (local is SuccessResult) return local
    if (!data.session.hasAccess()) return denied(request)
    val network =
        request.newBuilder().data(data.originalData).memoryCachePolicy(CachePolicy.WRITE_ONLY)
    val result = chain.proceed(network.build())
    if (result is SuccessResult) {
      withContext(Dispatchers.IO) {
        saveThumbnail(data.session, data.diskKey, result.drawable as? BitmapDrawable)
      }
    }
    return result
  }

  private fun saveThumbnail(session: Session, key: String, image: BitmapDrawable?) {
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
        // Coil snapshots require both files, even when the entry has no metadata.
        diskCache.fileSystem.write(editor.metadata) {}
        diskCache.fileSystem.write(editor.data) { writeAll(thumbnail) }
        editor.commit()
      } catch (_: IOException) {
        editor.abort()
        // An unavailable cache should still allow displaying a valid downloaded image.
      }
    }
  }

  private fun denied(request: ImageRequest) =
      ErrorResult(null, request, IOException("Fridge image session is no longer active"))

  private data class Scope(val userId: String, val householdId: String)

  private data class Thumbnail(val session: Session, val diskKey: String, val originalData: Any)

  private data class DiskThumbnail(val key: String)

  companion object {
    fun diskCache(directory: File): DiskCache =
        DiskCache.Builder().directory(directory).maxSizeBytes(DISK_CACHE_BYTES).build()

    private const val DISK_CACHE_BYTES = 50L * 1024 * 1024
    // A single rendition stays available if the layout width changes while offline.
    internal const val THUMBNAIL_PIXELS = 256
  }
}
