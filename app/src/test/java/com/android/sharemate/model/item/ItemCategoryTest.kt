// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks how stored category values, old free text included, map to the fixed list. */
class ItemCategoryTest {

  @Test
  fun everyEnumNameReadsBackAsItself() {
    for (category in ItemCategory.entries) {
      assertEquals(category, ItemCategory.fromStored(category.name))
    }
  }

  @Test
  fun knownOldSpellingsIgnoringCaseMapToTheirCategory() {
    for (text in listOf("fruit", "Fruits", "fruta", "fruits", " FRUIT ", "Frutas")) {
      assertEquals(text, ItemCategory.FRUITS, ItemCategory.fromStored(text))
    }
    assertEquals(ItemCategory.VEGETABLES, ItemCategory.fromStored("Légumes"))
    assertEquals(ItemCategory.DAIRY, ItemCategory.fromStored("dairy"))
    assertEquals(ItemCategory.MEAT_FISH, ItemCategory.fromStored("Meat & fish"))
    assertEquals(ItemCategory.DRINKS, ItemCategory.fromStored("Boissons"))
    assertEquals(ItemCategory.PANTRY, ItemCategory.fromStored("pantry"))
    assertEquals(ItemCategory.FROZEN, ItemCategory.fromStored("Surgelés"))
    assertEquals(ItemCategory.OTHER, ItemCategory.fromStored("other"))
  }

  @Test
  fun anyOtherTextMapsToOther() {
    for (text in listOf("Bio", "Prepared food", "groceries", "😀")) {
      assertEquals(text, ItemCategory.OTHER, ItemCategory.fromStored(text))
    }
  }

  @Test
  fun missingOrBlankValueIsNoCategory() {
    assertNull(ItemCategory.fromStored(null))
    assertNull(ItemCategory.fromStored(""))
    assertNull(ItemCategory.fromStored("   "))
  }
}
