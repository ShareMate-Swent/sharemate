// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model

/** Firestore collection names used by the application. */
object FirestoreCollections {
  const val USERS = "users"
  const val USER_PSEUDOS = "userPseudos"
  const val HOUSEHOLDS = "households"
  const val ITEMS = "items"
  const val RECEIPTS = "receipts"
  const val INVITE_CODES = "inviteCodes"
  /** Read-only shared expenses beneath a household document. */
  const val EXPENSES = "expenses"
  /** Sub-collection of a household document. */
  const val MESSAGES = "messages"
}

/** Fields of a user document holding the expiry notification preferences. */
object UserExpirationFields {
  const val PREFERENCES = "expirationPreferences"
  const val SOON_DAYS = "soonDays"
  const val SOON_REPEAT_DAYS = "soonRepeatDays"
  const val EXPIRED_REPEAT_DAYS = "expiredRepeatDays"

  /** Stored in place of a repeat interval when the user chose "never". */
  const val NEVER = 0
}

/** Fields of a user document. */
object UserFields {
  /** Name shown to other users instead of the Firebase id. */
  const val PSEUDO = "pseudo"
}
