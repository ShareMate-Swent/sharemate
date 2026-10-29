// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageUpdateResult
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import java.time.LocalDate
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeEditTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val repository = EditRepository()
  private val item =
      Item(
          id = "milk",
          name = "Milk",
          ownerId = "other-member",
          householdId = "household",
          quantity = 3,
          category = "Dairy",
          expirationDate = Date(0))
  private val otherItem = item.copy(id = "bread", name = "Bread", expirationDate = null)

  private fun show() {
    repository.items.value = listOf(item, otherItem)
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val vm =
        FridgeViewModel(
            repository,
            "user",
            "household",
            FoodImageSelector(imageRepository),
            BarcodeScanner { BarcodeScanResult.NotFound })
    compose.runOnUiThread { compose.activity.viewModelStore.put("fridge-edit-test", vm) }
    compose.setContent { SampleAppTheme(dynamicColor = false) { FridgeScreen(vm) } }
    openEdit()
  }

  private fun openEdit() {
    compose.onNodeWithTag("fridge_item_actions_${item.id}").performScrollTo().performClick()
    compose
        .onNodeWithTag(C.Tag.fridge_edit_prefix + item.id)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
  }

  private fun replace(tag: String, text: String) {
    compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
  }

  private fun save() {
    Espresso.closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
  }

  private fun cancel() {
    Espresso.closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).assertIsEnabled().performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
  }

  private fun assertFields(name: String, quantity: String, category: String, date: String) {
    listOf(
            C.Tag.fridge_name_input to name,
            C.Tag.fridge_quantity_input to quantity,
            C.Tag.fridge_category_input to category,
            C.Tag.fridge_expiration_input to date)
        .forEach { (tag, value) ->
          compose.onNodeWithTag(tag).performScrollTo().assertTextContains(value)
        }
  }

  private fun assertError(resource: Int) {
    compose
        .onNodeWithTag(C.Tag.fridge_form_error)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextEquals(compose.activity.getString(resource))
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
  }

  @Test
  fun prefilledEditSavesAllFieldsWithoutCreatingAnotherItem() {
    show()
    assertFields("Milk", "3", "Dairy", "1970-01-01")
    replace(C.Tag.fridge_name_input, "Cheese")
    replace(C.Tag.fridge_quantity_input, "4")
    replace(C.Tag.fridge_category_input, "Protein")
    replace(C.Tag.fridge_expiration_input, "2026-12-31")
    save()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.onNodeWithText("Cheese").assertIsDisplayed()
    compose.runOnIdle {
      val date = ExpirationDates.toDate(LocalDate.of(2026, 12, 31))
      assertEquals(listOf(item.id to ItemEdit("Cheese", 4, "Protein", date)), repository.edits)
      assertEquals(
          listOf(
              item.copy(name = "Cheese", quantity = 4, category = "Protein", expirationDate = date),
              otherItem),
          repository.items.value)
    }
  }

  @Test
  fun cancelDiscardsAllChangesAndReopeningRestoresOriginalFields() {
    show()
    replace(C.Tag.fridge_name_input, "Changed")
    replace(C.Tag.fridge_quantity_input, "7")
    replace(C.Tag.fridge_category_input, "Fruit")
    replace(C.Tag.fridge_expiration_input, "2027-01-01")
    cancel()
    openEdit()
    assertFields("Milk", "3", "Dairy", "1970-01-01")
    compose.runOnIdle {
      assertTrue(repository.edits.isEmpty())
      assertEquals(listOf(item, otherItem), repository.items.value)
    }
  }

  @Test
  fun invalidNameQuantityAndExpirationShowFeedbackWithoutSubmitting() {
    show()
    replace(C.Tag.fridge_name_input, " ")
    save()
    assertError(R.string.fridge_name_required)
    replace(C.Tag.fridge_name_input, "Milk")
    replace(C.Tag.fridge_quantity_input, "0")
    save()
    assertError(R.string.fridge_quantity_invalid)
    replace(C.Tag.fridge_quantity_input, "2")
    replace(C.Tag.fridge_expiration_input, "2026-02-30")
    save()
    assertError(R.string.fridge_date_invalid)
    compose.runOnIdle {
      assertTrue(repository.edits.isEmpty())
      assertEquals(listOf(item, otherItem), repository.items.value)
    }
  }

  @Test
  fun immediatelyRejectedEditKeepsDraftAndAllowsRetry() {
    repository.editFailure = IllegalStateException("Denied")
    show()
    replace(C.Tag.fridge_name_input, "Cheese")
    save()
    assertError(R.string.fridge_save_failed)
    compose.onNodeWithTag(C.Tag.fridge_name_input).performScrollTo().assertTextContains("Cheese")
    compose.runOnIdle {
      assertEquals(listOf(item, otherItem), repository.items.value)
      assertEquals(1, repository.edits.size)
      repository.editFailure = null
    }
    save()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.onNodeWithText("Cheese").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(2, repository.edits.size)
      assertEquals(item.copy(name = "Cheese"), repository.items.value.first())
      assertEquals(otherItem, repository.items.value.last())
    }
  }

  @Test
  fun immediatelyRejectedEditCanBeCancelledWithoutChangingOriginalItem() {
    repository.editFailure = IllegalStateException("Denied")
    show()
    replace(C.Tag.fridge_name_input, "Cheese")
    save()
    assertError(R.string.fridge_save_failed)
    cancel()
    openEdit()
    assertFields("Milk", "3", "Dairy", "1970-01-01")
    compose.onNodeWithTag(C.Tag.fridge_form_error).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(1, repository.edits.size)
      assertEquals(listOf(item, otherItem), repository.items.value)
    }
  }

  @Test
  fun offlineQueuedEditClosesDialogAndAllowsAnotherEditBeforeConfirmation() {
    val first = CompletableDeferred<Result<Unit>>()
    val second = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = first
    show()
    replace(C.Tag.fridge_name_input, "Cheese")
    save()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    openEdit()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Cheese")
    compose.runOnIdle { repository.editConfirmation = second }
    replace(C.Tag.fridge_name_input, "Yogurt")
    save()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.onNodeWithText("Yogurt").assertIsDisplayed()
    compose.runOnIdle {
      assertFalse(first.isCompleted)
      assertFalse(second.isCompleted)
      assertEquals(listOf("Cheese", "Yogurt"), repository.edits.map { it.second.name })
      assertEquals(item.copy(name = "Yogurt"), repository.items.value.first())
      assertEquals(otherItem, repository.items.value.last())
    }
  }

  @Test
  fun delayedRejectionShowsFeedbackWithoutOverwritingNewerOpenDraft() {
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.editConfirmation = confirmation
    show()
    replace(C.Tag.fridge_name_input, "Cheese")
    save()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    openEdit()
    replace(C.Tag.fridge_name_input, "Unsaved draft")
    replace(C.Tag.fridge_quantity_input, "5")
    compose.runOnIdle {
      repository.items.value = listOf(item, otherItem)
      confirmation.complete(Result.failure(IllegalStateException("Server denied")))
    }
    assertFields("Unsaved draft", "5", "Dairy", "1970-01-01")
    cancel()
    compose.onNodeWithText("Milk").assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.fridge_edit_sync_error)
        .assertIsDisplayed()
        .assertTextEquals(compose.activity.getString(R.string.fridge_edit_sync_failed, "Cheese"))
    compose.runOnIdle {
      assertEquals(1, repository.edits.size)
      assertEquals(listOf(item, otherItem), repository.items.value)
    }
  }

  private class EditRepository : ItemRepository {
    val items = MutableStateFlow<List<Item>>(emptyList())
    val edits = mutableListOf<Pair<String, ItemEdit>>()
    var editFailure: Exception? = null
    var editConfirmation: CompletableDeferred<Result<Unit>>? = null

    override fun getSharedItems(householdId: String) = items

    override fun getPrivateItems(userId: String) = error("Unexpected private query")

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("Not used in this test")

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

    override fun setItemSharing(
        itemId: String,
        sharing: com.android.sharemate.model.item.ItemSharing,
    ): ItemWrite = error("Sharing updates are not used in this test")

    override fun setItemStatus(itemId: String, status: ItemStatus) =
        error("Unexpected status change")
  }
}
