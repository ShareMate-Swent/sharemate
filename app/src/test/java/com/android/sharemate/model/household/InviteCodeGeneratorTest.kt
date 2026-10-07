// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeGeneratorTest {
  @Test
  fun generatedCodeHasExpectedLengthAndCharacters() {
    val code = InviteCodeGenerator(Random(1234)).generate()

    assertEquals(6, code.length)
    assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    assertTrue(code.none { it in "0O1I" })
  }

  @Test
  fun seededRandomProducesDeterministicCode() {
    val first = InviteCodeGenerator(Random(1234)).generate()
    val second = InviteCodeGenerator(Random(1234)).generate()

    assertEquals(first, second)
  }

  @Test
  fun successiveCallsProduceDifferentCodes() {
    val generator = InviteCodeGenerator(Random(1234))

    assertNotEquals(generator.generate(), generator.generate())
  }
}
