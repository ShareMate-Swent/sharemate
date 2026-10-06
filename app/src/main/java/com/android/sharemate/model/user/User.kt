// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

/** Represents an authenticated ShareMate user. */
data class User(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val householdId: String? = null
)
