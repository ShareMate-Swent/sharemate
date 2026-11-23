// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.CompletableDeferred
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

/** Image requests and access lifecycle for one authorized fridge session. */
interface FridgeImageCacheSession {
  val isActive: StateFlow<Boolean>
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
    private val isOnline: () -> Boolean = {
      val connectivity = context.getSystemService(ConnectivityManager::class.java)
      connectivity
          ?.getNetworkCapabilities(connectivity.activeNetwork)
          ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    },
    callFactory: Call.Factory? = null,
) {
  private val sessions = mutableMapOf<Scope, Session>()
  private val inFlight = mutableMapOf<MemoryCache.Key, CompletableDeferred<ImageResult?>>()
  private val loader =
      ImageLoader.Builder(context)
          .diskCache(diskCache)
          // Check connectivity once on a cache miss for both Firebase and HTTP.
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
      while (session.hasAccess()) {
        val pending = CompletableDeferred<ImageResult?>()
        val existing = synchronized(inFlight) { inFlight.putIfAbsent(memoryKey, pending) }
        val result =
            if (existing == null) {
              var loaded: ImageResult? = null
              try {
                load(chain, request, data, diskKey).also { loaded = it }
              } finally {
                synchronized(inFlight) { inFlight.remove(memoryKey) }
                pending.complete(loaded)
              }
            } else {
              // If the first card left composition, an active card can take over the load.
              when (val shared = existing.await()) {
                is SuccessResult -> shared.copy(request = request)
                is ErrorResult -> shared.copy(request = request)
                null -> continue
              }
            }
        if (session.hasAccess()) return result
      }
      return denied(request)
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
  ): ImageResult {
    val local = readThumbnail(chain, request, diskKey)
    if (local is SuccessResult) return local
    if (!isOnline()) return local
    if (!data.session.hasAccess()) return denied(request)
    val network = request.newBuilder().memoryCachePolicy(CachePolicy.WRITE_ONLY)
    when (val reference = data.reference) {
      is PersonalPhotoReference -> network.firebasePhoto(reference, data.downloadPhoto)
      is RemoteFridgeImageReference -> network.data(reference.url)
    }
    val result = chain.proceed(network.build())
    if (result is SuccessResult) {
      withContext(Dispatchers.IO) {
        saveThumbnail(data.session, diskKey, result.drawable as? BitmapDrawable)
      }
    }
    return result
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
            .fetcherFactory<DiskThumbnail> { data, _, _ ->
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
  }
}
