// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {

  @Test
  fun defaultMessageIsEmpty() {
    val message = Message()

    assertEquals("", message.id)
    assertEquals("", message.senderId)
    assertEquals("", message.senderName)
    assertEquals("", message.text)
    assertNull(message.timestamp)
  }

  @Test
  fun messageKeepsGivenValues() {
    val date = Date(1000L)
    val message = Message("m1", "u1", "Inge", "Hello", date)

    assertEquals("m1", message.id)
    assertEquals("u1", message.senderId)
    assertEquals("Inge", message.senderName)
    assertEquals("Hello", message.text)
    assertEquals(date, message.timestamp)
  }

  @Test
  fun isSentByMatchesSenderOnly() {
    val message = Message(senderId = "u1")

    assertTrue(message.isSentBy("u1"))
    assertFalse(message.isSentBy("u2"))
  }

  @Test
  fun isSentByIsFalseWithoutSender() {
    assertFalse(Message().isSentBy(""))
  }

  @Test
  fun normalizeTextTrimsWhitespace() {
    assertEquals("hi", Message.normalizeText("  hi \n"))
  }

  @Test
  fun blankTextIsInvalid() {
    assertFalse(Message.isValidText(""))
    assertFalse(Message.isValidText("   \n"))
  }

  @Test
  fun textUpToMaxLengthIsValid() {
    assertTrue(Message.isValidText("a".repeat(Message.MAX_TEXT_LENGTH)))
    assertFalse(Message.isValidText("a".repeat(Message.MAX_TEXT_LENGTH + 1)))
  }
}
