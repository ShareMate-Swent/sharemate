// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemTest {
  @Test
  fun oldConstructorArgumentsKeepTheirMeaningAndCategoryDefaultsToNull() {
    val expiration = Date(1234)
    val item = Item("item-id", "Milk", "test-user", "test-household", expiration)

    assertEquals("item-id", item.id)
    assertEquals("Milk", item.name)
    assertEquals("test-user", item.ownerId)
    assertEquals("test-household", item.householdId)
    assertEquals(expiration, item.expirationDate)
    assertNull(item.category)
    assertEquals(1, item.quantity)
    assertEquals(ItemStatus.ACTIVE, item.status)
  }

  @Test
  fun categoryIsOptionalAndCanBeProvided() {
    assertNull(Item().category)
    assertEquals("Dairy", Item(category = "Dairy").category)
  }

  @Test
  fun quantityAndStatusCanBeEditedWithoutChangingIdentity() {
    val original = Item(id = "id", ownerId = "owner", householdId = "household")
    for (status in ItemStatus.values()) {
      val edited = original.copy(quantity = 3, status = status)
      assertEquals(3, edited.quantity)
      assertEquals(status, edited.status)
      assertEquals(original.id, edited.id)
      assertEquals(original.ownerId, edited.ownerId)
      assertEquals(original.householdId, edited.householdId)
    }
  }
}
