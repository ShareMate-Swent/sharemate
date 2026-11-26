// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remembers, for each account, the households the server confirmed deleted.
 *
 * Firestore answers from its local cache when it cannot reach the server, and the cache can still
 * hold a household deleted just before the connection dropped. Reading this store tells the app to
 * ignore such a household, also after the app was restarted offline.
 */
interface DeletedHouseholdStore {

  /**
   * Records that the server deleted [householdId] for the account [userId].
   *
   * @throws IOException if the record cannot be saved.
   */
  suspend fun markDeleted(userId: String, householdId: String)

  /** Whether [householdId] was recorded as deleted for the account [userId]. */
  suspend fun isDeleted(userId: String, householdId: String): Boolean
}

/**
 * [DeletedHouseholdStore] kept in SharedPreferences, one set of household IDs per account.
 *
 * Reads and writes run on [dispatcher]. Writes are serialized with a [Mutex] and committed
 * synchronously, so a record survives the app being closed right after; an [IOException] is thrown
 * when a write cannot be committed.
 *
 * @param dispatcher Runs the disk access.
 */
class AndroidDeletedHouseholdStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
) : DeletedHouseholdStore {
  private val applicationContext = context.applicationContext
  private val preferences
    get() = applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  private val writeMutex = Mutex()

  override suspend fun markDeleted(userId: String, householdId: String) {
    withContext(dispatcher) {
      writeMutex.withLock {
        val key = key(userId)
        val ids = preferences.getStringSet(key, emptySet()).orEmpty() + householdId
        if (!preferences.edit().putStringSet(key, ids).commit()) {
          throw IOException("Could not save the deleted household")
        }
      }
    }
  }

  override suspend fun isDeleted(userId: String, householdId: String): Boolean =
      withContext(dispatcher) {
        householdId in preferences.getStringSet(key(userId), emptySet()).orEmpty()
      }

  private fun key(userId: String) = "$KEY_PREFIX$userId"

  private companion object {
    const val FILE_NAME = "deleted_households"
    const val KEY_PREFIX = "deleted_by_"
  }
}
