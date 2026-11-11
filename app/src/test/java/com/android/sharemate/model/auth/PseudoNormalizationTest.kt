// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PseudoNormalizationTest {
  @Test
  fun normalizesCaseOuterWhitespaceAndCompatibilityCharacters() {
    assertEquals(normalizePseudo("Alex"), normalizePseudo("  ALEX  "))
    assertEquals(normalizePseudo("ShareMate"), normalizePseudo("\uFF33hareMate"))
    assertEquals(pseudoClaimId(" Alex "), pseudoClaimId("alex"))
    assertEquals(pseudoClaimId("Alex"), pseudoClaimId("Alex\u200B "))
  }
}
