// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PseudoValidationTest {
  @Test
  fun validatesTrimmedBoundariesInUnicodeCodePoints() {
    for (value in listOf("", " ", "\u200B\u0301\u00A0")) {
      assertEquals(PseudoValidationError.REQUIRED, validatePseudo(value))
    }
    for (value in listOf("x", "x".repeat(MAX_PSEUDO_LENGTH), "😀".repeat(MAX_PSEUDO_LENGTH))) {
      assertNull(validatePseudo("\u200B${value}\u00A0"))
    }
    for (value in listOf("x".repeat(MAX_PSEUDO_LENGTH + 1), "😀".repeat(MAX_PSEUDO_LENGTH + 1))) {
      assertEquals(PseudoValidationError.TOO_LONG, validatePseudo(value))
    }
  }
}
