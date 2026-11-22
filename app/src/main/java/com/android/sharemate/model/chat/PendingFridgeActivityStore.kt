// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A confirmed or in-progress fridge badge that must survive process restarts.
 *
 * @property id Stable action key in `itemId:TYPE` format used for chat deduplication.
 * @property householdId Household that owns the item and chat.
 * @property userId Sender who performed the item action.
 * @property type Fridge action represented by the badge.
 * @property itemName Item name captured for the badge.
 */
data class PendingFridgeActivity(
    val id: String,
    val householdId: String,
    val userId: String,
    val type: MessageType,
    val itemName: String,
)

/** Durable storage for fridge activity badges awaiting chat publication. */
interface PendingFridgeActivityStore {
  /**
   * Adds or replaces an activity in the durable queue, identified by [PendingFridgeActivity.id].
   */
  suspend fun save(activity: PendingFridgeActivity)

  /** Removes an activity after the chat document is acknowledged. */
  suspend fun remove(activity: PendingFridgeActivity)

  /** Lists valid pending activities for [householdId] and [userId]. */
  suspend fun list(householdId: String, userId: String): List<PendingFridgeActivity>
}

/**
 * SharedPreferences-backed [PendingFridgeActivityStore].
 *
 * Read-modify-write operations run on [Dispatchers.IO] and are serialized with [Mutex] so an app
 * process never performs two queue mutations concurrently.
 */
class AndroidPendingFridgeActivityStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PendingFridgeActivityStore {
  private val applicationContext = context.applicationContext
  private val preferences
    get() = applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  override suspend fun save(activity: PendingFridgeActivity) {
    mutate(activity.householdId, activity.userId) { activities ->
      activities.removeAll { it.id == activity.id }
      activities += activity
    }
  }

  override suspend fun remove(activity: PendingFridgeActivity) {
    mutate(activity.householdId, activity.userId) { activities ->
      activities.removeAll { it.id == activity.id }
    }
  }

  override suspend fun list(
      householdId: String,
      userId: String,
  ): List<PendingFridgeActivity> =
      withContext(dispatcher) { mutationMutex.withLock { readActivities(householdId, userId) } }

  private suspend fun mutate(
      householdId: String,
      userId: String,
      update: (MutableList<PendingFridgeActivity>) -> Unit,
  ) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          val activities = readActivities(householdId, userId).toMutableList()
          update(activities)
          writeActivities(householdId, userId, activities)
        }
      }

  private fun readActivities(
      householdId: String,
      userId: String,
  ): List<PendingFridgeActivity> {
    val value = preferences.getString(scopeKey(householdId, userId), null) ?: return emptyList()
    return try {
      val array = JSONArray(value)
      buildList {
        for (index in 0 until array.length()) {
          val activity = parseActivity(array.optJSONObject(index), householdId, userId)
          if (activity != null) add(activity)
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun parseActivity(
      json: JSONObject?,
      householdId: String,
      userId: String,
  ): PendingFridgeActivity? {
    if (json == null) return null
    return try {
      val id = json.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return null
      val storedHousehold =
          json.optString(FIELD_HOUSEHOLD_ID).takeIf { it.isNotBlank() } ?: return null
      val storedUser = json.optString(FIELD_USER_ID).takeIf { it.isNotBlank() } ?: return null
      val type =
          when (json.optString(FIELD_TYPE)) {
            MessageType.EATEN.name -> MessageType.EATEN
            MessageType.THROWN.name -> MessageType.THROWN
            else -> return null
          }
      val itemName = json.optString(FIELD_ITEM_NAME).trim()
      if (storedHousehold != householdId || storedUser != userId || itemName.isBlank()) return null
      PendingFridgeActivity(id, storedHousehold, storedUser, type, itemName)
    } catch (_: Exception) {
      null
    }
  }

  private fun writeActivities(
      householdId: String,
      userId: String,
      activities: List<PendingFridgeActivity>,
  ) {
    val array =
        JSONArray().apply {
          activities.forEach { activity ->
            put(
                JSONObject()
                    .put(FIELD_ID, activity.id)
                    .put(FIELD_HOUSEHOLD_ID, activity.householdId)
                    .put(FIELD_USER_ID, activity.userId)
                    .put(FIELD_TYPE, activity.type.name)
                    .put(FIELD_ITEM_NAME, activity.itemName))
          }
        }
    preferences.edit().putString(scopeKey(householdId, userId), array.toString()).apply()
  }

  private fun scopeKey(householdId: String, userId: String): String =
      "$SCOPE_PREFIX$householdId:$userId"

  private companion object {
    const val FILE_NAME = "pending_fridge_activities"
    const val SCOPE_PREFIX = "scope:"
    const val FIELD_ID = "id"
    const val FIELD_HOUSEHOLD_ID = "householdId"
    const val FIELD_USER_ID = "userId"
    const val FIELD_TYPE = "type"
    const val FIELD_ITEM_NAME = "itemName"
    val mutationMutex = Mutex()
  }
}
