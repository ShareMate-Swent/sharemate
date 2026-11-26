// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal fun ContentResolver.openPhotoStream(uri: Uri, signal: CancellationSignal): InputStream? {
  val asset = openAssetFileDescriptor(uri, "r", signal) ?: return null
  try {
    // Closing the returned stream also closes the asset descriptor.
    return asset.createInputStream()
  } catch (failure: Exception) {
    asset.close()
    throw failure
  }
}

/** Bounds the caller's wait even when a provider ignores cancellation or blocks reading a pipe. */
internal object BoundedPhotoRead {
  const val TIMEOUT_MS = 5_000L
  private const val WORKER_COUNT = 2

  suspend fun <T : Any> run(
      uri: Uri,
      timeoutMessage: String,
      busyMessage: String,
      discard: (T) -> Unit,
      read: (CancellationSignal, checkActive: () -> Unit) -> T,
  ): T {
    val workers = if (uri.scheme == ContentResolver.SCHEME_FILE) localWorkers else providerWorkers
    var completed: T? = null
    try {
      return withTimeoutOrNull(TIMEOUT_MS) {
            val value =
                suspendCancellableCoroutine<T> { continuation ->
                  val signal = CancellationSignal()
                  // Remote cancellation can itself block. Dispatch it independently.
                  continuation.invokeOnCancellation {
                    cancellationWorkers.execute { signal.cancel() }
                  }
                  try {
                    workers.execute {
                      if (!continuation.isActive) return@execute
                      try {
                        val result =
                            read(signal) {
                              if (!continuation.isActive)
                                  throw CancellationException("Photo read cancelled")
                            }
                        continuation.resume(result) { _, dropped, _ -> discard(dropped) }
                      } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                      }
                    }
                  } catch (failure: RejectedExecutionException) {
                    continuation.resumeWithException(IOException(busyMessage, failure))
                  }
                }
            completed = value
            value
          }
          ?.also { completed = null } ?: throw IOException(timeoutMessage)
    } finally {
      // The timeout may still win after the continuation has delivered its value.
      completed?.let(discard)
    }
  }

  private fun pool(name: String) =
      ThreadPoolExecutor(
          0,
          WORKER_COUNT,
          30L,
          TimeUnit.SECONDS,
          SynchronousQueue(),
          { task -> Thread(task, name).apply { isDaemon = true } },
      )

  // Workers own their resources until blocked calls return. With no queue, further reads fail fast.
  private val providerWorkers = pool("provider-photo-loader")
  private val localWorkers = pool("local-photo-loader")
  private val cancellationWorkers =
      pool("photo-cancellation").apply {
        rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy()
      }
}
