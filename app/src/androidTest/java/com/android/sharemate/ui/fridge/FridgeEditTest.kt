// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.resources.C
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeEditTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val repository = EditRepository()
  private val store = ViewModelStore()
  private val item =
      Item(
          id = "milk",
          name = "Milk",
          ownerId = "other-member",
          householdId = "household",
          quantity = 3,
          category = "Dairy",
          expirationDate = Date(0))

  @After fun clear() = store.clear()

  private fun show() {
    repository.emitItems(listOf(item))
    val vm = FridgeViewModel(repository, "user", "household")
    store.put("fridge", vm)
    compose.setContent { MaterialTheme { FridgeScreen(vm) } }
    compose.onNodeWithTag("fridge_edit_milk").performClick()
  }

  @Test
  fun prefilledEditSavesAllFieldsWithoutCreatingAnotherItem() {
    show()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.fridge_name_input)
        .assertTextContains("Milk")
        .performTextReplacement("Cheese")
    compose
        .onNodeWithTag("fridge_quantity_input")
        .assertTextContains("3")
        .performTextReplacement("4")
    compose
        .onNodeWithTag(C.Tag.fridge_category_input)
        .assertTextContains("Dairy")
        .performScrollTo()
        .performTextReplacement("Protein")
    compose
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .assertTextContains("1970-01-01")
        .performScrollTo()
        .performTextReplacement("2026-12-31")
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.onNodeWithText("Cheese").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals("milk", repository.edits.single().first)
      assertEquals(4, repository.edits.single().second.quantity)
      assertEquals("Protein", repository.edits.single().second.category)
      assertEquals(1, repository.edits.size)
    }
  }

  @Test
  fun cancelKeepsOriginalItemAndReopeningRestoresValues() {
    show()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextReplacement("Changed")
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).performClick()
    compose.onNodeWithText("Milk").assertIsDisplayed()
    compose.onNodeWithTag("fridge_edit_milk").performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Milk")
    compose.runOnIdle { assertTrue(repository.edits.isEmpty()) }
  }

  @Test
  fun validationAndRejectedWritesKeepFormOpenWithFeedback() {
    show()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextReplacement("")
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_form_error).assertTextEquals("Enter an item name.")
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextReplacement("Milk")
    compose.onNodeWithTag("fridge_quantity_input").performTextReplacement("0")
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose
        .onNodeWithTag(C.Tag.fridge_form_error)
        .assertTextEquals("Enter a whole number from 1 to 2147483647.")
    compose.onNodeWithTag("fridge_quantity_input").performTextReplacement("2")
    compose
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performScrollTo()
        .performTextReplacement("2026-02-30")
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose
        .onNodeWithTag(C.Tag.fridge_form_error)
        .assertTextEquals("Enter a valid date in YYYY-MM-DD format.")
    compose
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performScrollTo()
        .performTextReplacement("")
    compose.runOnIdle { repository.editFailure = IllegalStateException("Denied") }
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose
        .onNodeWithTag(C.Tag.fridge_form_error)
        .assertTextEquals("Could not save the item. Please try again.")
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).performClick()
    compose.onNodeWithText("Milk").assertIsDisplayed()
  }

  @Test
  fun queuedEditClosesFormAndLaterRejectionShowsFeedback() {
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = confirmation
    show()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextReplacement("Cheese")
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.onNodeWithText("Cheese").assertIsDisplayed()
    compose.onNodeWithTag("fridge_edit_milk").assertIsNotEnabled()
    compose.runOnIdle {
      repository.emitItems(listOf(item))
      confirmation.complete(Result.failure(IllegalStateException("Server denied")))
    }
    compose.onNodeWithText("Milk").assertIsDisplayed()
    compose.onNodeWithTag("fridge_edit_sync_error").assertIsDisplayed()
  }

  private class EditRepository : ItemRepository {
    private val items = MutableStateFlow<List<Item>>(emptyList())
    val edits = mutableListOf<Pair<String, ItemEdit>>()
    var editFailure: Exception? = null
    var editConfirmation: CompletableDeferred<Result<Unit>>? = null

    fun emitItems(value: List<Item>) {
      items.value = value
    }

    override fun getSharedItems(householdId: String) = items

    override fun getPrivateItems(userId: String) = error("Unexpected private query")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite {
      edits += itemId to edit
      editFailure?.let {
        return ItemWrite.Rejected(it)
      }
      items.value =
          items.value.map {
            if (it.id == itemId)
                it.copy(
                    name = edit.name,
                    quantity = edit.quantity,
                    category = edit.category,
                    expirationDate = edit.expirationDate)
            else it
          }
      return ItemWrite.Queued(editConfirmation ?: CompletableDeferred(Result.success(Unit)))
    }

    override suspend fun addItem(item: Item): String? = error("Editing must never add an item")

    override suspend fun deleteItem(itemId: String) = error("Unexpected deletion")

    override fun deleteItemQueued(itemId: String) = error("Unexpected deletion")

    override fun setItemStatus(itemId: String, status: ItemStatus) =
        error("Unexpected status change")
  }
}
