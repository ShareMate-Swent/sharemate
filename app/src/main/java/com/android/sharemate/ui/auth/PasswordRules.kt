// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.exceeds

/**
 * The rules for choosing a new password. They apply to sign-up only: logging in sends the password
 * as typed, so an account created before the maximum existed can still log in.
 */
internal object PasswordRules {
  /** Shortest new password. */
  const val MIN_LENGTH = 6

  /**
   * The first error of a new [password] typed with its [confirmation], or null when both are
   * acceptable. The length is checked before the confirmation, as the form shows one error at a
   * time.
   */
  fun signUpError(password: String, confirmation: String): AuthUiError? =
      when {
        password.length < MIN_LENGTH -> AuthUiError.WEAK_PASSWORD
        password.exceeds(TextLimits.PASSWORD) -> AuthUiError.PASSWORD_TOO_LONG
        password != confirmation -> AuthUiError.PASSWORD_MISMATCH
        else -> null
      }
}
