// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** In-memory [ExpirationPreferencesRepository] for tests. */
class FakeExpirationPreferencesRepository(
    initial: Map<String, ExpirationPreferences> = emptyMap(),
) : ExpirationPreferencesRepository {
  private val stored = MutableStateFlow(initial)

  /** When set, [save] throws it instead of storing anything. */
  var saveFailure: Exception? = null

  /** When set, [get] and [observe] throw it instead of reading anything. */
  var readFailure: Exception? = null

  /** Every [save] call, in order. */
  val saved = mutableListOf<Pair<String, ExpirationPreferences>>()

  override fun observe(userId: String): Flow<ExpirationPreferences> = flow {
    readFailure?.let { throw it }
    stored.collect { emit(it[userId] ?: ExpirationPreferences()) }
  }

  override suspend fun get(userId: String): ExpirationPreferences {
    readFailure?.let { throw it }
    return stored.value[userId] ?: ExpirationPreferences()
  }

  override suspend fun save(userId: String, preferences: ExpirationPreferences) {
    saveFailure?.let { throw it }
    saved += userId to preferences
    stored.value = stored.value + (userId to preferences)
  }

  val updates = mutableListOf<Pair<String, ExpirationChange>>()

  override suspend fun update(userId: String, change: ExpirationChange) {
    saveFailure?.let { throw it }
    updates += userId to change
    stored.value = stored.value + (userId to change.applyTo(get(userId)))
  }
}
