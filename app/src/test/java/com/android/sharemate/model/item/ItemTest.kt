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
  }

  @Test
  fun categoryIsOptionalAndCanBeProvided() {
    assertNull(Item().category)
    assertEquals("Dairy", Item(category = "Dairy").category)
  }
}
