// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface FridgeImageReference

internal data class RemoteFridgeImageReference(val url: String) : FridgeImageReference

data class LoadedFridgeImage(val bytes: ByteArray, val fromDisk: Boolean = false)

/** Access to the private image cache from one authorized fridge session. */
interface FridgeImageCacheSession {
  val isActive: StateFlow<Boolean>

  fun memoryKey(reference: FridgeImageReference): String

  suspend fun load(
      reference: FridgeImageReference,
      download: suspend () -> ByteArray,
  ): LoadedFridgeImage

  suspend fun revoke()
}

/**
 * One byte budget across all accounts and households. The namespace directories let us purge their
 * contents without keeping an index of cache keys.
 */
internal class FridgeImageCache(
    private val directory: File,
    private val storageBucket: String,
    private val isCurrentUser: (String) -> Boolean,
    private val isOnline: () -> Boolean,
    private val evictMemory: (String) -> Unit = {},
    private val maxBytes: Long = 50L * 1024 * 1024,
) {
  private val sessions = mutableMapOf<Namespace, Session>()
  private val diskLock = Mutex()
  // A download only reserves its stripe; unrelated hits and purges can still use the disk.
  private val downloadLocks = Array(16) { Mutex() }

  fun session(userId: String, householdId: String): FridgeImageCacheSession =
      synchronized(sessions) {
        val namespace = Namespace(userId, hash(userId), hash(householdId))
        sessions.getOrPut(namespace) { Session(namespace) }
      }

  suspend fun purgeAccount(userId: String) = purge(hash(userId), householdKey = null)

  suspend fun purgeHousehold(userId: String, householdId: String) =
      purge(hash(userId), hash(householdId))

  private suspend fun purge(userKey: String, householdKey: String?, session: Session? = null) =
      withContext(NonCancellable + Dispatchers.IO) {
        diskLock.withLock {
          if (session?.isActive?.value == false) return@withLock
          val revoked =
              synchronized(sessions) {
                val matching =
                    sessions.filterKeys {
                      it.userKey == userKey &&
                          (householdKey == null || it.householdKey == householdKey)
                    }
                matching.values.forEach { it.invalidate() }
                sessions.keys.removeAll(matching.keys)
                matching.values
              }
          val account = File(directory, userKey)
          val target = householdKey?.let { File(account, it) } ?: account
          target.deleteRecursively()
          revoked.forEach { evictMemory(it.memoryPrefix) }
        }
      }

  private inner class Session(private val namespace: Namespace) : FridgeImageCacheSession {
    private val active = MutableStateFlow(true)
    override val isActive = active.asStateFlow()
    val memoryPrefix =
        "fridge-photo:${namespace.userKey}:${namespace.householdKey}:${UUID.randomUUID()}:"

    fun invalidate() {
      active.value = false
    }

    override fun memoryKey(reference: FridgeImageReference) = memoryPrefix + imageKey(reference)

    override suspend fun revoke() = purge(namespace.userKey, namespace.householdKey, this)

    override suspend fun load(
        reference: FridgeImageReference,
        download: suspend () -> ByteArray,
    ): LoadedFridgeImage =
        withContext(Dispatchers.IO) {
          val key = imageKey(reference)
          val folder = File(File(directory, namespace.userKey), namespace.householdKey)
          val file = File(folder, "$key.photo")
          read(file, reference)?.let {
            return@withContext LoadedFridgeImage(it, fromDisk = true)
          }
          val stripe = downloadLocks[Math.floorMod(file.hashCode(), downloadLocks.size)]
          stripe.withLock {
            read(file, reference)?.let {
              return@withLock LoadedFridgeImage(it, fromDisk = true)
            }
            if (!isOnline()) throw IOException("Image is not cached and the device is offline")
            val bytes = download()
            diskLock.withLock {
              checkAccess()
              write(file, bytes)
            }
            LoadedFridgeImage(bytes)
          }
        }

    private suspend fun read(file: File, reference: FridgeImageReference): ByteArray? =
        diskLock.withLock {
          checkAccess()
          if (!file.isFile) return@withLock null
          try {
            val context = currentCoroutineContext()
            val bytes =
                file.inputStream().use { PhotoBytes.readBounded(it) { context.ensureActive() } }
            require(bytes.isNotEmpty())
            if (reference is PersonalPhotoReference) PhotoBytes.contentType(bytes)
            file.setLastModified(System.currentTimeMillis())
            bytes
          } catch (_: IOException) {
            file.delete()
            null
          } catch (_: IllegalArgumentException) {
            file.delete()
            null
          }
        }

    private fun checkAccess() {
      if (!active.value || !isCurrentUser(namespace.userId)) {
        throw IOException("Fridge image session is no longer active")
      }
    }
  }

  // diskLock is held during the write, so no reader can see a partial file and no purge can race
  // it.
  private fun write(file: File, bytes: ByteArray) {
    if (bytes.size > maxBytes) return
    val folder = checkNotNull(file.parentFile)
    try {
      if (!makeRoom(bytes.size) || !(folder.isDirectory || folder.mkdirs())) return
      val temporary = File.createTempFile("photo-", ".tmp", folder)
      try {
        temporary.writeBytes(bytes)
        temporary.renameTo(file)
      } finally {
        temporary.delete()
      }
    } catch (_: IOException) {
      // A full/unavailable disk should still allow the fetched image to display.
    }
  }

  private fun makeRoom(incomingBytes: Int): Boolean {
    val files =
        directory.listFiles().orEmpty().flatMap { account ->
          account.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().asList() }
        }
    files.filter { it.extension == "tmp" }.forEach { it.delete() }
    val entries = files.filter { it.extension == "photo" }
    var size = entries.sumOf(File::length)
    for (entry in entries.sortedBy(File::lastModified)) {
      if (size + incomingBytes <= maxBytes) break
      val length = entry.length()
      if (entry.delete()) size -= length
    }
    return size + incomingBytes <= maxBytes
  }

  private fun imageKey(reference: FridgeImageReference): String =
      when (reference) {
        // Keep the existing key so already viewed Firebase photos survive the update.
        is PersonalPhotoReference -> hash("$storageBucket/${reference.path}")
        is RemoteFridgeImageReference -> hash("remote:${reference.url}")
      }

  private data class Namespace(val userId: String, val userKey: String, val householdKey: String)

  private companion object {
    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
          "%02x".format(it)
        }
  }
}
