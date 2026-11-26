// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local eviction never writes a receipt-deletion tombstone or calls a remote delete API. */
class ReceiptCacheLifecycle(context: Context) {
  private val app = context.applicationContext
  private val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
  private val generations = preferences.all.filterKeys { it.startsWith(EPOCH) }

  fun generation(home: String): Long = preferences.getLong(EPOCH + home, 0)

  fun isCurrent(home: String): Boolean =
      synchronized(lock) {
        !preferences.getBoolean(BLOCKED + home, false) &&
            generation(home) == (generations[EPOCH + home] as? Long ?: 0L)
      }

  /** Serializes the final disk write with eviction, including downloads finishing after leave. */
  fun <T> write(home: String, block: () -> T): T =
      synchronized(lock) {
        check(isCurrent(home)) { "Receipt household access has ended" }
        block()
      }

  fun commit(home: String, editor: SharedPreferences.Editor): Boolean =
      write(home) { editor.commit() }

  fun trackDownload(viewer: String, home: String, id: String) =
      write(home) {
        val scope = JSONArray(listOf(viewer, home)).toString()
        check(preferences.edit().putString("download:$scope:$id", id).commit())
      }

  /** Only call with an authoritative server membership snapshot, never an offline empty result. */
  suspend fun reconcile(viewer: String, homes: Set<String>) {
    val known =
        preferences.all.keys
            .mapNotNull { key ->
              val start = key.indexOf('[')
              val end = key.indexOf(']', start)
              if (start < 0 || end < start) null
              else
                  runCatching {
                        val scope = JSONArray(key.substring(start, end + 1))
                        scope.getString(1).takeIf { scope.getString(0) == viewer }
                      }
                      .getOrNull()
            }
            .toSet()
    (known - homes).forEach { evict(viewer, it) }
    homes.forEach { activate(it) }
  }

  /** Reactivates the cache after confirmed membership, persisting the barrier change off main. */
  suspend fun activate(home: String) =
      withContext(Dispatchers.IO) {
        synchronized(lock) { check(preferences.edit().remove(BLOCKED + home).commit()) }
      }

  suspend fun evict(viewer: String, home: String) =
      withContext(Dispatchers.IO) {
        synchronized(lock) {
          val keys =
              preferences.all.keys.filter { key ->
                val start = key.indexOf('[')
                val end = key.indexOf(']', start)
                if (start < 0 || end < start) false
                else
                    runCatching {
                          val scope = JSONArray(key.substring(start, end + 1))
                          scope.getString(0) == viewer && scope.getString(1) == home
                        }
                        .getOrDefault(false)
              }
          // Photo files are shared by receipt ID; another viewer may still need the same image.
          val photos = photoIds(keys) - photoIds(preferences.all.keys - keys.toSet())
          // Persist the barrier first. A crash can then only leave data to clean up on retry.
          check(
              preferences
                  .edit()
                  .putBoolean(BLOCKED + home, true)
                  .putLong(EPOCH + home, generation(home) + 1)
                  .commit())
          photos.forEach { id ->
            require(id.matches(Regex("[A-Za-z0-9_-]+")))
            val photo = File(app.filesDir, "receipts/$id.jpg")
            check(!photo.exists() || photo.delete()) { "Could not remove receipt photo" }
          }
          val editor = preferences.edit()
          keys.forEach(editor::remove)
          check(editor.commit())
        }
      }

  private fun photoIds(keys: Collection<String>): Set<String> =
      keys
          .mapNotNull { key ->
            when {
              key.startsWith("details:") ->
                  preferences
                      .getString(key, null)
                      ?.let(::JSONObject)
                      ?.optString(com.android.sharemate.model.item.InventorySubmissionFields.ID)
              key.startsWith("download:") -> preferences.getString(key, null)
              else -> null
            }
          }
          .toSet()

  private companion object {
    const val EPOCH = "receipt-epoch:"
    const val BLOCKED = "receipt-blocked:"
    val lock = Any()
  }
}
