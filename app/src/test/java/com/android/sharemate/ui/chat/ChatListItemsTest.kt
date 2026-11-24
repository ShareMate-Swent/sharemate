// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListItemsTest {
  private val zone: ZoneId = ZoneOffset.UTC
  private val today = LocalDate.of(2026, 11, 24)

  private fun at(day: LocalDate, hour: Int = 12): Date =
      Date.from(day.atTime(hour, 0).atZone(zone).toInstant())

  private fun text(id: String, sender: String, time: Date? = at(today)) =
      Message(id, sender, sender, "text $id", time)

  private fun activity(id: String, sender: String, time: Date? = at(today)) =
      Message(id, sender, sender, "", time, MessageType.EATEN, "Milk")

  private fun build(vararg messages: Message) = buildChatItems(messages.toList(), "me", today, zone)

  private fun List<ChatListItem>.messageItems() = filterIsInstance<MessageItem>()

  @Test
  fun noMessagesGiveNoRows() {
    assertTrue(build().isEmpty())
  }

  @Test
  fun separatorPrecedesTheFirstMessageOfEachDay() {
    val yesterday = today.minusDays(1)
    val items =
        build(text("1", "a", at(yesterday)), text("2", "a", at(yesterday, 13)), text("3", "a"))
    assertEquals(
        listOf("day:$yesterday", "message:1", "message:2", "day:$today", "message:3"),
        items.map { it.key })
    assertEquals(listOf(yesterday, today), items.filterIsInstance<DaySeparator>().map { it.day })
  }

  @Test
  fun dayFollowsTheGivenZone() {
    // 23:30 UTC on the 23rd is already the 24th one hour east of UTC.
    val lateEvening = Date.from(today.minusDays(1).atTime(23, 30).atZone(zone).toInstant())
    val items =
        buildChatItems(listOf(text("1", "a", lateEvening)), "me", today, ZoneOffset.ofHours(1))
    assertEquals(DaySeparator(today), items.first())
  }

  @Test
  fun pendingMessageWithoutTimestampBelongsToToday() {
    val items = build(text("1", "a", at(today.minusDays(3))), text("2", "me", null))
    assertEquals(DaySeparator(today), items[2])
    assertEquals(4, items.size)
  }

  @Test
  fun runOfOtherMemberShowsNameOnFirstAndAvatarOnLast() {
    val items = build(text("1", "a"), text("2", "a"), text("3", "a")).messageItems()
    assertEquals(listOf(true, false, false), items.map { it.showName })
    assertEquals(listOf(false, false, true), items.map { it.showAvatar })
  }

  @Test
  fun singleMessageShowsBothNameAndAvatar() {
    val item = build(text("1", "a")).messageItems().single()
    assertTrue(item.showName && item.showAvatar)
  }

  @Test
  fun anotherSenderEndsTheRun() {
    val items = build(text("1", "a"), text("2", "b"), text("3", "a")).messageItems()
    assertEquals(listOf(true, true, true), items.map { it.showName })
    assertEquals(listOf(true, true, true), items.map { it.showAvatar })
  }

  @Test
  fun activityEndsTheRunAndShowsNoNameOrAvatar() {
    val items = build(text("1", "a"), activity("2", "a"), text("3", "a")).messageItems()
    assertEquals(listOf(true, false, true), items.map { it.showName })
    assertEquals(listOf(true, false, true), items.map { it.showAvatar })
  }

  @Test
  fun newDayEndsTheRun() {
    val items =
        build(text("1", "a", at(today.minusDays(1))), text("2", "a", at(today))).messageItems()
    assertEquals(listOf(true, true), items.map { it.showName })
    assertEquals(listOf(true, true), items.map { it.showAvatar })
  }

  @Test
  fun incidentsJoinTextRuns() {
    val incident = text("2", "a").copy(type = MessageType.INCIDENT)
    val items = build(text("1", "a"), incident).messageItems()
    assertEquals(listOf(true, false), items.map { it.showName })
    assertEquals(listOf(false, true), items.map { it.showAvatar })
  }

  @Test
  fun ownMessagesAndActivitiesAreOwnWithoutNameOrAvatar() {
    val items = build(text("1", "me"), text("2", "me"), activity("3", "me")).messageItems()
    assertEquals(listOf(true, true, true), items.map { it.own })
    assertTrue(items.none { it.showName || it.showAvatar })
  }

  @Test
  fun othersActivitiesAreNotOwn() {
    assertEquals(false, build(activity("1", "a")).messageItems().single().own)
  }

  @Test
  fun separatorAndMessageKeysNeverCollide() {
    val tricky = text("2026-11-24", "a")
    val items = build(tricky)
    assertNotEquals(items[0].key, items[1].key)
  }

  @Test
  fun headerParticipantsExcludeMeAndKeepFirstAppearanceOrder() {
    val messages =
        listOf(
            text("1", "alex"),
            text("2", "me"),
            activity("3", "lucia"),
            text("4", "sam"),
            text("5", "alex"))
    assertEquals(
        listOf("alex", "lucia", "sam"), headerParticipants(messages, "me").map { it.senderId })
  }

  @Test
  fun headerParticipantsAreCappedAtThree() {
    val messages = listOf("a", "b", "c", "d").mapIndexed { i, id -> text("$i", id) }
    assertEquals(listOf("a", "b", "c"), headerParticipants(messages, "me").map { it.senderId })
  }

  @Test
  fun headerParticipantsUseTheLatestNameAndSkipMissingSenders() {
    val messages =
        listOf(
            Message("1", "a", "Sam", "hi"),
            Message("2", "", "Ghost", "hi"),
            Message("3", "a", "Samuel", "hi"))
    assertEquals(listOf(ChatParticipant("a", "Samuel")), headerParticipants(messages, "me"))
  }

  @Test
  fun headerParticipantsAreEmptyWithoutOtherSenders() {
    assertTrue(headerParticipants(emptyList(), "me").isEmpty())
    assertTrue(headerParticipants(listOf(text("1", "me")), "me").isEmpty())
  }

  @Test
  fun initialsOfOneWordTakeItsFirstTwoLetters() {
    assertEquals("LU", initials("Lucía"))
    assertEquals("SA", initials("sam"))
    assertEquals("J", initials("J"))
  }

  @Test
  fun initialsOfSeveralWordsTakeTheFirstLetterOfTheFirstTwo() {
    assertEquals("AM", initials("Alex Martin"))
    assertEquals("AM", initials("  alex   martin  dupont "))
  }

  @Test
  fun initialsOfBlankNamesAreEmpty() {
    assertEquals("", initials(""))
    assertEquals("", initials("   "))
  }

  @Test
  fun initialsNeverSplitAnEmoji() {
    val pizza = "🍕"
    assertEquals(pizza + pizza, initials(pizza + pizza + pizza))
    assertEquals(pizza + "B", initials("$pizza Bob"))
  }
}
