// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model

/** Firestore collection names used by the application. */
object FirestoreCollections {
  const val USERS = "users"
  /** Public profiles that household members can read; see `UserProfileFields`. */
  const val PROFILES = "profiles"
  /** Private, non-enumerable reservations for normalized public pseudos. */
  const val PSEUDO_CLAIMS = "pseudoClaims"
  const val HOUSEHOLDS = "households"
  const val ITEMS = "items"
  const val RECEIPTS = "receipts"
  const val INVITE_CODES = "inviteCodes"
  /** Read-only shared expenses beneath a household document. */
  const val EXPENSES = "expenses"
  const val SETTLEMENTS = "settlements"
  /** Sub-collection of a household document. */
  const val MESSAGES = "messages"
  const val MEMBER_NAMES = "memberNames"
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

/** Fields of a public profile document. */
object UserProfileFields {
  /** Also used by the household name directory and legacy user documents. */
  const val DISPLAY_NAME = "displayName"
  const val PHOTO_URL = "photoUrl"
  const val EMAIL = "email"
}
