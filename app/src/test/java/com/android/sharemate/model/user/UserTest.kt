package com.android.sharemate.model.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserTest {

  @Test
  fun defaultUserHasNoHousehold() {
    val user = User()

    assertEquals("", user.uid)
    assertEquals("", user.displayName)
    assertEquals("", user.email)
    assertNull(user.householdId)
  }

  @Test
  fun userKeepsGivenValues() {
    val user =
        User(uid = "u1", displayName = "Inge", email = "inge@example.com", householdId = "h1")

    assertEquals("u1", user.uid)
    assertEquals("Inge", user.displayName)
    assertEquals("inge@example.com", user.email)
    assertEquals("h1", user.householdId)
  }
}
