// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import kotlin.random.Random

/** Generates six-character household invite codes. */
class InviteCodeGenerator(private val random: Random = Random.Default) {
  /** Generates a code containing uppercase letters and digits. */
  fun generate(): String =
      buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
      }

  private companion object {
    const val CODE_LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  }
}
