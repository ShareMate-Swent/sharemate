// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import com.android.sharemate.model.text.TextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordRulesTest {
  private fun error(password: String, confirmation: String = password) =
      PasswordRules.signUpError(password, confirmation)

  @Test
  fun lengthBoundsAreInclusive() {
    assertEquals(AuthUiError.WEAK_PASSWORD, error(""))
    assertEquals(AuthUiError.WEAK_PASSWORD, error("a".repeat(PasswordRules.MIN_LENGTH - 1)))
    assertNull(error("a".repeat(PasswordRules.MIN_LENGTH)))
    assertNull(error("a".repeat(TextLimits.PASSWORD)))
    assertEquals(AuthUiError.PASSWORD_TOO_LONG, error("a".repeat(TextLimits.PASSWORD + 1)))
  }

  @Test
  fun limitCountsCodePointsNotUtf16Units() {
    assertNull(error("😀".repeat(TextLimits.PASSWORD)))
    assertEquals(AuthUiError.PASSWORD_TOO_LONG, error("😀".repeat(TextLimits.PASSWORD + 1)))
  }

  @Test
  fun confirmationMustMatchOnceTheLengthIsValid() {
    assertEquals(AuthUiError.PASSWORD_MISMATCH, error("secret1", "secret2"))
    assertNull(error("secret1", "secret1"))
    assertEquals(AuthUiError.WEAK_PASSWORD, error("abc", "different"))
    assertEquals(
        AuthUiError.PASSWORD_TOO_LONG, error("a".repeat(TextLimits.PASSWORD + 1), "different"))
  }
}
