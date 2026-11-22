// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A confirmed or in-progress fridge badge that must survive process restarts. */
data class PendingFridgeActivity(
    val id: String,
    val householdId: String,
    val userId: String,
    val type: MessageType,
    val itemName: String,
)

/** Durable storage for fridge activity badges awaiting chat publication. */
interface PendingFridgeActivityStore {
  /** Adds or replaces an activity in the durable queue. */
  suspend fun save(activity: PendingFridgeActivity)

  /** Removes an activity after the chat document is acknowledged. */
  suspend fun remove(activity: PendingFridgeActivity)

  /** Lists pending activities for one household and user. */
  suspend fun list(householdId: String, userId: String): List<PendingFridgeActivity>
}

/** SharedPreferences-backed [PendingFridgeActivityStore]. */
class AndroidPendingFridgeActivityStore(
    context: Context,
) : PendingFridgeActivityStore {
  private val applicationContext = context.applicationContext
  private val preferences
    get() = applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  override suspend fun save(activity: PendingFridgeActivity) {
    val activities = loadLocked(activity.householdId, activity.userId).toMutableList()
    activities.removeAll { it.id == activity.id }
    activities += activity
    persistLocked(activity.householdId, activity.userId, activities)
  }

  override suspend fun remove(activity: PendingFridgeActivity) {
    val activities =
        loadLocked(activity.householdId, activity.userId).filter { it.id != activity.id }
    persistLocked(activity.householdId, activity.userId, activities)
  }

  override suspend fun list(
      householdId: String,
      userId: String,
  ): List<PendingFridgeActivity> = loadLocked(householdId, userId)

  private fun loadLocked(householdId: String, userId: String): List<PendingFridgeActivity> {
    val value = preferences.getString(scopeKey(householdId, userId), null) ?: return emptyList()
    val array = JSONArray(value)
    return List(array.length()) { index ->
      val json = array.getJSONObject(index)
      PendingFridgeActivity(
          id = json.getString(FIELD_ID),
          householdId = json.getString(FIELD_HOUSEHOLD_ID),
          userId = json.getString(FIELD_USER_ID),
          type = MessageType.fromStored(json.getString(FIELD_TYPE)),
          itemName = json.getString(FIELD_ITEM_NAME),
      )
    }
  }

  private fun persistLocked(
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
    preferences.edit().putString(scopeKey(householdId, userId), array.toString()).commit()
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
  }
}
