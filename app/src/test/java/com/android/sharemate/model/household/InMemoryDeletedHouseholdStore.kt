// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

/**
 * In-memory [DeletedHouseholdStore] for tests. Sharing one instance between two view models stands
 * for the device keeping the record across an app restart.
 *
 * @property writeFailure Thrown by [markDeleted] when set.
 * @property readFailure Thrown by [isDeleted] when set.
 */
class InMemoryDeletedHouseholdStore : DeletedHouseholdStore {
  private val deleted = mutableMapOf<String, MutableSet<String>>()
  var writeFailure: Exception? = null
  var readFailure: Exception? = null

  override suspend fun markDeleted(userId: String, householdId: String) {
    writeFailure?.let { throw it }
    deleted.getOrPut(userId) { mutableSetOf() } += householdId
  }

  override suspend fun isDeleted(userId: String, householdId: String): Boolean {
    readFailure?.let { throw it }
    return householdId in deleted[userId].orEmpty()
  }
}
