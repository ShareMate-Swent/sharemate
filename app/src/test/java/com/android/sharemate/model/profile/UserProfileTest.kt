// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.profile

import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class UserProfileTest {
  @Test
  fun publicProfileKeepsNamePhotoAndEmailOfTheSession() {
    val session =
        AuthSession(
            uid = "user",
            email = "user@example.com",
            displayName = "Ada Lovelace",
            photoUrl = "https://example.com/ada.png",
            provider = SignInProvider.GOOGLE,
        )

    assertEquals(
        UserProfile("user", "Ada Lovelace", "https://example.com/ada.png", "user@example.com"),
        session.toPublicProfile())
  }

  @Test
  fun blankValuesBecomeMissing() {
    val session = AuthSession(uid = "user", email = " ", displayName = "", photoUrl = "  ")

    assertEquals(UserProfile("user"), session.toPublicProfile())
  }

  @Test
  fun emailAccountWithoutNameOrPhotoKeepsOnlyItsEmail() {
    val session = AuthSession(uid = "user", email = "user@example.com")

    assertEquals(UserProfile("user", email = "user@example.com"), session.toPublicProfile())
  }

  @Test
  fun signInRefreshPreservesChosenNameAndUsesProviderNameForMissingName() {
    val profile = UserProfile("user", "Provider", "photo", "user@example.com")
    assertEquals(profile.copy(displayName = "Chosen"), profile.withExistingDisplayName("Chosen"))
    assertEquals(profile, profile.withExistingDisplayName(null))
    assertEquals(profile, profile.withExistingDisplayName("  "))
    assertEquals(
        UserProfile("user", "Chosen"), UserProfile("user").withExistingDisplayName("Chosen"))
  }
}
