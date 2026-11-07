// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserExpirationFields.EXPIRED_REPEAT_DAYS
import com.android.sharemate.model.UserExpirationFields.NEVER
import com.android.sharemate.model.UserExpirationFields.PREFERENCES
import com.android.sharemate.model.UserExpirationFields.SOON_DAYS
import com.android.sharemate.model.UserExpirationFields.SOON_REPEAT_DAYS
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/**
 * Keeps the preferences in the user's profile document, in an `expirationPreferences` map. A repeat
 * of 0 days stands for "never".
 */
class FirebaseExpirationPreferencesRepository(private val firestore: FirebaseFirestore) :
    ExpirationPreferencesRepository {

  private fun document(userId: String) =
      firestore.collection(FirestoreCollections.USERS).document(userId)

  override fun observe(userId: String): Flow<ExpirationPreferences> =
      callbackFlow {
            val registration =
                document(userId).addSnapshotListener { snapshot, error ->
                  if (error != null) {
                    close(error)
                    return@addSnapshotListener
                  }
                  if (snapshot != null) trySend(snapshot.toPreferences())
                }
            awaitClose { registration.remove() }
          }
          .distinctUntilChanged()

  override suspend fun get(userId: String): ExpirationPreferences =
      document(userId).get().await().toPreferences()

  override suspend fun save(userId: String, preferences: ExpirationPreferences) {
    val value = preferences.sanitized()
    val data =
        mapOf(
            PREFERENCES to
                mapOf(
                    SOON_DAYS to value.soonDays,
                    SOON_REPEAT_DAYS to (value.soonRepeatDays ?: NEVER),
                    EXPIRED_REPEAT_DAYS to (value.expiredRepeatDays ?: NEVER)))
    document(userId).set(data, SetOptions.merge()).await()
  }

  override suspend fun update(userId: String, change: ExpirationChange) {
    val (field, value) =
        when (change) {
          is ExpirationChange.SoonDays ->
              SOON_DAYS to change.days.coerceIn(ExpirationPreferences.SOON_DAYS_RANGE)
          is ExpirationChange.SoonRepeatDays -> SOON_REPEAT_DAYS to storedRepeat(change.days)
          is ExpirationChange.ExpiredRepeatDays -> EXPIRED_REPEAT_DAYS to storedRepeat(change.days)
        }
    document(userId).set(mapOf(PREFERENCES to mapOf(field to value)), SetOptions.merge()).await()
  }

  private fun storedRepeat(days: Int?) =
      days?.coerceIn(ExpirationPreferences.REPEAT_DAYS_RANGE) ?: NEVER

  private fun DocumentSnapshot.toPreferences(): ExpirationPreferences {
    val map = get(PREFERENCES) as? Map<*, *> ?: return ExpirationPreferences()
    val defaults = ExpirationPreferences()
    return ExpirationPreferences(
            soonDays = (map[SOON_DAYS] as? Number)?.toInt() ?: defaults.soonDays,
            soonRepeatDays = map.repeat(SOON_REPEAT_DAYS, defaults.soonRepeatDays),
            expiredRepeatDays = map.repeat(EXPIRED_REPEAT_DAYS, defaults.expiredRepeatDays))
        .sanitized()
  }

  private fun Map<*, *>.repeat(key: String, default: Int?): Int? {
    val days = (this[key] as? Number)?.toInt() ?: return default
    return if (days <= NEVER) null else days
  }
}
