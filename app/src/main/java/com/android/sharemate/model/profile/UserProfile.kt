// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.text.limitedTo
import com.android.sharemate.model.user.MAX_PSEUDO_LENGTH

/**
 * The part of a user's account that the other members of their household can see.
 *
 * It is stored apart from the private user document, because security rules can only share whole
 * documents: the private one also holds the household link and notification preferences.
 *
 * @property uid User identifier.
 * @property displayName Account name; null when the account has none.
 * @property photoUrl Account photo URL; null when there is none.
 * @property email Account email; null when the provider does not share one.
 */
data class UserProfile(
    val uid: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val email: String? = null,
)

/** Retain the user's chosen display name when refreshing provider details at sign-in. */
internal fun UserProfile.withExistingDisplayName(existing: String?): UserProfile =
    copy(displayName = (existing?.takeIf { it.isNotBlank() } ?: displayName)?.truncatePseudo())

/**
 * The public profile of this session, with blank values omitted and names limited in code points.
 */
fun AuthSession.toPublicProfile(): UserProfile =
    UserProfile(
        uid = uid,
        displayName = displayName?.takeIf { it.isNotBlank() }?.truncatePseudo(),
        photoUrl = photoUrl?.takeIf { it.isNotBlank() },
        email = email?.takeIf { it.isNotBlank() },
    )

private fun String.truncatePseudo(): String = limitedTo(MAX_PSEUDO_LENGTH)
