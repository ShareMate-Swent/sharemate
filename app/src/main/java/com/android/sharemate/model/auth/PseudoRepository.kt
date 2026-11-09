// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import com.android.sharemate.model.user.trimPseudoPadding
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Atomically reserves normalized pseudos for authenticated users. */
fun interface PseudoRepository {
  /**
   * Reserves [pseudo] for [uid], or succeeds without changing data if [uid] already owns it or is
   * one of its grandfathered owners.
   *
   * @throws AuthException with [AuthError.PSEUDO_TAKEN] when another user owns the pseudo.
   */
  suspend fun claim(uid: String, pseudo: String)

  /** Releases a current claim after a successful rename; grandfathered claims remain reserved. */
  suspend fun release(uid: String, pseudo: String) = Unit
}

internal fun normalizePseudo(pseudo: String): String =
    Normalizer.normalize(pseudo.trimPseudoPadding(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

internal fun pseudoClaimId(pseudo: String): String {
  val bytes =
      MessageDigest.getInstance("SHA-256")
          .digest(normalizePseudo(pseudo).toByteArray(StandardCharsets.UTF_8))
  return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
}
