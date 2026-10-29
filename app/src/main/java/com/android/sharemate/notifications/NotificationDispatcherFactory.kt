// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.content.Context
import com.android.sharemate.MainActivity
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.notifications.expiration.ExpirationNotificationSource
import com.android.sharemate.notifications.expiration.ResourceExpirationNotificationFormatter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Wires the production notification pipeline. The list of sources is the one place to extend: add a
 * [NotificationSource] for each feature that can notify (expirations, receipts, recipes, ...).
 */
object NotificationDispatcherFactory {
  fun create(context: Context): NotificationDispatcher {
    val firestore = FirebaseFirestore.getInstance()
    val recipientProvider =
        SessionRecipientProvider(
            FirebaseAuthRepository(FirebaseAuth.getInstance()),
            HouseholdRepositoryFirestore(firestore))
    val sources =
        listOf<NotificationSource>(
            ExpirationNotificationSource(
                itemRepository = FirebaseItemRepository(firestore),
                recipientProvider = recipientProvider,
                formatter = ResourceExpirationNotificationFormatter(context.resources)),
        )
    return NotificationDispatcher(
        sources, AndroidNotificationPublisher(context, MainActivity::class.java))
  }
}
