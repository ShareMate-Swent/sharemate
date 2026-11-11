// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserExpirationFields.EXPIRED_REPEAT_DAYS
import com.android.sharemate.model.UserExpirationFields.NEVER
import com.android.sharemate.model.UserExpirationFields.PREFERENCES
import com.android.sharemate.model.UserExpirationFields.SOON_DAYS
import com.android.sharemate.model.UserExpirationFields.SOON_REPEAT_DAYS
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.ProducerScope
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
            val registration = document(userId).addSnapshotListener(snapshotListener())
            awaitClose { registration.remove() }
          }
          .distinctUntilChanged()

  /** Sends each snapshot as preferences; a listener error closes the flow with that error. */
  private fun ProducerScope<ExpirationPreferences>.snapshotListener() =
      EventListener<DocumentSnapshot> { snapshot, error ->
        when {
          error != null -> close(error)
          snapshot != null -> trySend(snapshot.toPreferences())
        }
      }

  override suspend fun get(userId: String): ExpirationPreferences =
      document(userId).get().await().toPreferences()

  override suspend fun save(userId: String, preferences: ExpirationPreferences) {
    val data =
        mapOf(
            PREFERENCES to
                mapOf(
                    SOON_DAYS to preferences.soonDays,
                    SOON_REPEAT_DAYS to (preferences.soonRepeatDays ?: NEVER),
                    EXPIRED_REPEAT_DAYS to (preferences.expiredRepeatDays ?: NEVER)))
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

  /** Stored values cannot be trusted, so each one is read on its own and brought into range. */
  private fun DocumentSnapshot.toPreferences(): ExpirationPreferences {
    val map = get(PREFERENCES) as? Map<*, *> ?: return ExpirationPreferences()
    val defaults = ExpirationPreferences()
    return ExpirationPreferences.coerced(
        soonDays = (map[SOON_DAYS] as? Number)?.toInt() ?: defaults.soonDays,
        soonRepeatDays = map.repeat(SOON_REPEAT_DAYS, defaults.soonRepeatDays),
        expiredRepeatDays = map.repeat(EXPIRED_REPEAT_DAYS, defaults.expiredRepeatDays))
  }

  /** A stored repeat interval; [NEVER] or less means no repeat, a missing value the [default]. */
  private fun Map<*, *>.repeat(key: String, default: Int?): Int? {
    val days = (this[key] as? Number)?.toInt() ?: return default
    return if (days <= NEVER) null else days
  }
}
