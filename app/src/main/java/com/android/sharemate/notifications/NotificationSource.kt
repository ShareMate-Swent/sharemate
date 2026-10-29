// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.HouseholdRepository
import kotlinx.coroutines.flow.first

/**
 * Decides which notifications are due right now for one feature (expirations, receipts, ...).
 *
 * A source only computes; showing the notifications is the job of [NotificationPublisher]. To add a
 * new kind of notification, implement this interface and register it in
 * [NotificationDispatcherFactory].
 */
fun interface NotificationSource {
  suspend fun dueNotifications(): List<AppNotification>
}

/** Who the notifications are for: the signed-in user and the household they belong to. */
data class NotificationRecipient(val userId: String, val householdId: String)

/** Resolves the [NotificationRecipient], shared by every source that is scoped to the user. */
fun interface NotificationRecipientProvider {
  /** Returns null when nobody is signed in or the user has no household yet. */
  suspend fun current(): NotificationRecipient?
}

class SessionRecipientProvider(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
) : NotificationRecipientProvider {
  override suspend fun current(): NotificationRecipient? {
    val session = authRepository.session.first() ?: return null
    val household = householdRepository.getHouseholdForUser(session.uid) ?: return null
    return NotificationRecipient(session.uid, household.id)
  }
}
