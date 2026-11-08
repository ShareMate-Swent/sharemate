// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ExpirationPreferencesRepository] for tests. */
class FakeExpirationPreferencesRepository(
    initial: Map<String, ExpirationPreferences> = emptyMap(),
) : ExpirationPreferencesRepository {
  private val stored = MutableStateFlow(initial)
  var saveFailure: Exception? = null
  val saved = mutableListOf<Pair<String, ExpirationPreferences>>()

  override fun observe(userId: String): Flow<ExpirationPreferences> =
      kotlinx.coroutines.flow.flow {
        stored.collect { emit(it[userId] ?: ExpirationPreferences()) }
      }

  override suspend fun get(userId: String) = stored.value[userId] ?: ExpirationPreferences()

  override suspend fun save(userId: String, preferences: ExpirationPreferences) {
    saveFailure?.let { throw it }
    saved += userId to preferences
    stored.value = stored.value + (userId to preferences)
  }
}
