// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.staging

import android.content.Context
import android.content.SharedPreferences
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import com.android.sharemate.model.item.ItemQuantity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InventorySubmissionStoreTest {
  @Test
  fun receiptDetailsAndEditsSurviveRestartAndStayScoped() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = java.util.UUID.randomUUID().toString()
    val draft =
        InventorySubmission(
            id,
            "details-owner",
            "details-home",
            listOf(StagedItem("a", "Apple", 1, 125)),
            draft = true)
    val details = ReceiptDetails(id, "details-owner", "details-home", "file:///receipt.jpg", 100L)

    assertTrue(runCatching { store.saveDetails(details) }.isFailure)
    store.save(draft)
    store.saveDetails(details)
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(details, restored.details("details-owner", "details-home", id))
    assertNull(restored.details("other-owner", "details-home", id))
    assertNull(restored.details("details-owner", "other-home", id))

    val saved = details.copy(savedAtMillis = 250L)
    restored.saveDetails(saved)
    assertEquals(saved, store.details("details-owner", "details-home", id))
    assertEquals(draft, store.loadById("details-owner", "details-home", id))
  }

  @Test
  fun deletingOneDraftRemovesItsMetadataWithoutTouchingOtherReceipts() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val first =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            "delete-owner",
            "delete-home",
            listOf(StagedItem("a", "Apple", 1, 125)),
            draft = true)
    val second =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            "delete-owner",
            "delete-home",
            listOf(StagedItem("b", "Bread", 2, 350)),
            draft = true)
    store.save(first)
    store.save(second)
    val metadata =
        ReceiptDetails(first.id, first.ownerId, first.householdId, "file:///first.jpg", 100L)
    store.saveDetails(metadata)

    store.deleteDraft(first.ownerId, first.householdId, first.id)
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(listOf(second), restored.list(first.ownerId, first.householdId))
    assertNull(restored.loadById(first.ownerId, first.householdId, first.id))
    assertNull(restored.details(first.ownerId, first.householdId, first.id))
    assertEquals(second, restored.loadById(second.ownerId, second.householdId, second.id))
    store.deleteDraft(first.ownerId, first.householdId, first.id)
    assertEquals(listOf(second), restored.list(first.ownerId, first.householdId))
  }

  @Test
  fun expenseNullPurchaseDraftStaysOutsideReceiptHistory() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val purchaseStore = AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
    val historyStore = AndroidInventorySubmissionStore(app, dispatcher)
    val purchase =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "draft-owner",
            "draft-home",
            listOf(StagedItem("milk", "Milk", 2, 1000)),
            draft = true)
    purchaseStore.save(purchase)

    assertNull(historyStore.load("draft-owner", "draft-home"))
    assertNull(historyStore.loadById("draft-owner", "draft-home", purchase.id))
    assertTrue(historyStore.list("draft-owner", "draft-home").isEmpty())

    val receipt =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abd",
            "draft-owner",
            "draft-home",
            listOf(StagedItem("bread", "Bread", 1, 350)),
            draft = true)
    historyStore.save(receipt)
    assertEquals(listOf(receipt), historyStore.list("draft-owner", "draft-home"))
    assertNull(historyStore.loadById("draft-owner", "draft-home", purchase.id))
    assertEquals(purchase, purchaseStore.load("draft-owner", "draft-home"))
    app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun expenseBearingLegacyEntryStaysOutsideReceiptHistory() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val purchaseStore = AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
    val historyStore = AndroidInventorySubmissionStore(app, dispatcher)
    val purchaseId = "12345678-1234-1234-1234-123456789abc"
    val rows = listOf(StagedItem("milk", "Milk", 2, 1000))
    val purchase =
        InventorySubmission(
            purchaseId,
            "purchase-owner",
            "purchase-home",
            rows,
            expense =
                com.android.sharemate.model.finance.SharedExpense(
                    purchaseId,
                    "purchase-home",
                    "purchase-owner",
                    1000,
                    mapOf("purchase-owner" to 1000L)))
    purchaseStore.save(purchase)

    assertNull(historyStore.load("purchase-owner", "purchase-home"))
    assertNull(historyStore.loadById("purchase-owner", "purchase-home", purchaseId))
    assertTrue(historyStore.list("purchase-owner", "purchase-home").isEmpty())

    val draft =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abd",
            "purchase-owner",
            "purchase-home",
            rows,
            draft = true)
    historyStore.save(draft)
    assertEquals(listOf(draft), historyStore.list("purchase-owner", "purchase-home"))
    assertNull(historyStore.loadById("purchase-owner", "purchase-home", purchaseId))
    assertEquals(purchase, purchaseStore.load("purchase-owner", "purchase-home"))
    app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun multipleReceiptsSurviveRestartAndStayInTheirOwnScope() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val first =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "multi-owner",
            "multi-home",
            listOf(StagedItem("a", "Apple", 1, 100)),
            draft = true)
    val second =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abd",
            "multi-owner",
            "multi-home",
            listOf(StagedItem("b", "Bread", 2, 350)),
            draft = true)
    store.save(first)
    store.save(second)

    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(listOf(second, first), restored.list("multi-owner", "multi-home"))
    assertEquals(first, restored.loadById("multi-owner", "multi-home", first.id))
    assertNull(restored.loadById("another-owner", "multi-home", first.id))
    assertNull(restored.loadById("multi-owner", "another-home", first.id))

    val edited =
        InventorySubmission(
            first.id,
            first.ownerId,
            first.householdId,
            listOf(first.items.single().copy(name = "Green apple")),
            draft = true)
    restored.save(edited)
    assertEquals(edited, store.loadById("multi-owner", "multi-home", first.id))
    assertEquals(second, store.loadById("multi-owner", "multi-home", second.id))
  }

  @Test
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun concurrentSaveAcrossStoresCannotBeRemovedBySetAside() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val writer = AndroidInventorySubmissionStore(app, dispatcher)
    val id = "12345678-1234-1234-1234-123456789abc"
    val rows = listOf(StagedItem("milk", "Milk", 2, 1000))
    val rejected =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows,
            expense =
                com.android.sharemate.model.finance.SharedExpense(
                    id, "home", "owner", 1000, mapOf("owner" to 1000L)),
            inventoryConfirmed = true,
            expenseProgress = com.android.sharemate.model.item.ExpenseProgress.REJECTED)
    val draft =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(), "owner", "home", rows, draft = true)
    writer.save(rejected)
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val intercepted =
        mock(
            SharedPreferences::class.java,
            org.mockito.AdditionalAnswers.delegatesTo<SharedPreferences>(preferences))
    val context = mock(Context::class.java)
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE)))
        .thenReturn(intercepted)
    lateinit var replacement: Job
    doAnswer { invocation ->
          // Start the competing save after the archive has read the old active value.
          val value = preferences.getString(invocation.getArgument(0), null)
          replacement =
              launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                writer.save(draft)
              }
          value
        }
        .`when`(intercepted)
        .getString(anyString(), isNull())
    AndroidInventorySubmissionStore(context, dispatcher).setAside(rejected)
    replacement.join()
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(draft, restored.load("owner", "home"))
    assertEquals(listOf(rejected), restored.deferred("owner", "home"))
    assertTrue(runCatching { restored.setAside(rejected) }.isFailure)
    assertEquals(draft, restored.load("owner", "home"))
    assertEquals(listOf(rejected), restored.deferred("owner", "home"))
  }

  @Test
  fun purchaseProgressSurvivesRestartAndLegacyRowsStayInventoryOnly() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = "12345678-1234-1234-1234-123456789abc"
    val rows = listOf(StagedItem("milk", "Milk", 2, 1000))
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "owner", 1000, mapOf("owner" to 500L, "bob" to 500L))
    for (progress in com.android.sharemate.model.item.ExpenseProgress.entries) {
      val purchase =
          InventorySubmission(
              id,
              "owner",
              "home",
              rows,
              expense = expense,
              inventoryConfirmed = true,
              expenseProgress = progress)
      store.save(purchase)
      assertEquals(purchase, AndroidInventorySubmissionStore(app, dispatcher).load("owner", "home"))
    }
    store.save(InventorySubmission(id, "owner", "home", rows))
    assertNull(store.load("owner", "home")?.expense)
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val key = "receipt:${JSONArray(listOf("owner", "home"))}:$id"
    val json = JSONObject(checkNotNull(preferences.getString(key, null)))
    assertFalse(json.has(Fields.EXPENSE))
    val purchase = InventorySubmission(id, "owner", "home", rows, expense = expense)
    store.save(purchase)
    val original = checkNotNull(preferences.getString(key, null))
    for (mutate in
        listOf<(JSONObject) -> Unit>(
            { it.put(Fields.INVENTORY_CONFIRMED, "true") },
            { it.put(Fields.EXPENSE_PROGRESS, "invalid") },
            { it.getJSONObject(Fields.EXPENSE).put("totalAmountCents", "1000") },
            { it.getJSONObject(Fields.EXPENSE).put("paidBy", "other") },
            { it.getJSONObject(Fields.EXPENSE).getJSONObject("shares").put("bob", 1.5) })) {
      val corrupt = JSONObject(original).also(mutate)
      preferences.edit().putString(key, corrupt.toString()).commit()
      assertTrue(runCatching { store.load("owner", "home") }.isFailure)
    }
    preferences.edit().clear().commit()
  }

  @Test
  fun deferredRejectionSurvivesNewDraftAndIsScopedAndRecoverable() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = "12345678-1234-1234-1234-123456789abc"
    val rows = listOf(StagedItem("milk", "Milk", 2, 1000))
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "owner", 1000, mapOf("owner" to 1000L))
    val rejected =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows,
            expense = expense,
            inventoryConfirmed = true,
            expenseProgress = com.android.sharemate.model.item.ExpenseProgress.REJECTED)
    store.save(rejected)
    store.setAside(rejected)
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertNull(restored.load("owner", "home"))
    assertEquals(listOf(rejected), restored.deferred("owner", "home"))
    assertTrue(restored.deferred("other", "home").isEmpty())
    assertTrue(restored.deferred("owner", "other").isEmpty())
    val draft =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(), "owner", "home", rows, draft = true)
    restored.save(draft)
    assertEquals(draft, restored.load("owner", "home"))
    assertEquals(listOf(rejected), restored.deferred("owner", "home"))
    restored.save(rejected)
    assertEquals(rejected, restored.load("owner", "home"))
    assertTrue(restored.deferred("owner", "home").isEmpty())
    app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun recreatedStoreRestoresExactPayloadAndSeparatesUserAndHouseholdScopes() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val first = AndroidInventorySubmissionStore(app, dispatcher)
    val rows =
        listOf(
            StagedItem("milk", "Milk", 2, 480),
            StagedItem("unknown", "Bread", 1, null),
            StagedItem("free", "Gift", 1, 0))
    val saved = InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    first.save(saved)
    first.save(InventorySubmission(saved.id, "other", saved.householdId, saved.items))
    first.save(InventorySubmission(saved.id, saved.ownerId, "another", saved.items))
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(saved, restored.load("owner", "home"))
    assertEquals(
        InventorySubmission(saved.id, "other", saved.householdId, saved.items),
        restored.load("other", "home"))
    assertEquals(
        InventorySubmission(saved.id, saved.ownerId, "another", saved.items),
        restored.load("owner", "another"))
    assertNull(restored.load("missing", "home"))
    val changed =
        InventorySubmission(
            saved.id,
            saved.ownerId,
            saved.householdId,
            listOf(rows.first().copy(name = "Corrected milk")))
    restored.save(changed)
    assertEquals(changed, first.load("owner", "home"))
    val draft =
        InventorySubmission(saved.id, saved.ownerId, saved.householdId, saved.items, draft = true)
    restored.save(draft)
    assertEquals(draft, AndroidInventorySubmissionStore(app, dispatcher).load("owner", "home"))
  }

  @Test
  fun corruptOrMismatchedJournalFailsRatherThanPretendingThereIsNoSubmission() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val store = AndroidInventorySubmissionStore(app, StandardTestDispatcher(testScheduler))
    val owned =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abd",
            "owner",
            "home",
            listOf(StagedItem("bread", "Bread", 1, 350)))
    store.save(owned)
    val key = "receipt:${JSONArray(listOf("owner", "home"))}:${owned.id}"
    preferences.edit().putString(key, "not valid json").commit()
    assertTrue(runCatching { store.load("owner", "home") }.isFailure)
    val other =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "other",
            "home",
            listOf(StagedItem("milk", "Milk", 2, Long.MAX_VALUE)))
    store.save(other)
    val foreignKey = "receipt:${JSONArray(listOf("other", "home"))}:${other.id}"
    preferences.edit().putString(key, preferences.getString(foreignKey, null)).commit()
    assertTrue(runCatching { store.load("owner", "home") }.isFailure)
    assertEquals(other, store.load("other", "home"))
    preferences.edit().clear().commit()
  }

  @Test
  fun missingOrNonBooleanDraftFlagCannotRestoreAnUnconfirmedReceipt() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidInventorySubmissionStore(app, StandardTestDispatcher(testScheduler))
    val draft =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 480)),
            draft = true)
    store.save(draft)
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val key = "receipt:${JSONArray(listOf(draft.ownerId, draft.householdId))}:${draft.id}"
    val original = checkNotNull(preferences.getString(key, null))

    for (value in listOf(null, JSONObject.NULL, "true", "false", 1)) {
      val json = JSONObject(original)
      if (value == null) json.remove(Fields.DRAFT) else json.put(Fields.DRAFT, value)
      assertTrue(preferences.edit().putString(key, json.toString()).commit())
      assertTrue(
          "Reject draft flag $value (${value?.javaClass?.simpleName})",
          runCatching { store.load(draft.ownerId, draft.householdId) }.isFailure)
    }
  }

  @Test
  fun failedDiskCommitIsReportedToTheCaller() = runTest {
    val context = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE)))
        .thenReturn(preferences)
    `when`(preferences.edit()).thenReturn(editor)
    `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
    `when`(editor.remove(anyString())).thenReturn(editor)
    `when`(editor.commit()).thenReturn(false)
    val store = AndroidInventorySubmissionStore(context, StandardTestDispatcher(testScheduler))
    val submission =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 2, 480)))
    assertTrue(runCatching { store.save(submission) }.exceptionOrNull() is IllegalStateException)
    verify(editor).commit()
  }

  @Test
  fun malformedJournalFieldsCannotChangeIdentityOrCoerceQuantitiesAndPrices() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidInventorySubmissionStore(app, StandardTestDispatcher(testScheduler))
    val saved =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", ItemQuantity.MAX, Long.MAX_VALUE)))
    store.save(saved)
    assertEquals(saved, store.load("owner", "home"))
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val key = "receipt:${JSONArray(listOf("owner", "home"))}:${saved.id}"
    val original = checkNotNull(preferences.getString(key, null))
    for ((field, value) in
        listOf(
            Fields.QUANTITY to "2",
            Fields.QUANTITY to 1.5,
            Fields.QUANTITY to 0,
            Fields.QUANTITY to -1,
            Fields.QUANTITY to Int.MAX_VALUE.toLong() + 1,
            Fields.PRICE to "480",
            Fields.PRICE to 4.8,
            Fields.NAME to 42)) {
      val json = JSONObject(original)
      json.getJSONArray(Fields.ROWS).getJSONObject(0).put(field, value)
      preferences.edit().putString(key, json.toString()).commit()
      assertTrue("Reject $field=$value", runCatching { store.load("owner", "home") }.isFailure)
    }
    val foreign = JSONObject(original).put(Fields.HOUSEHOLD, "another")
    preferences.edit().putString(key, foreign.toString()).commit()
    assertTrue(runCatching { store.load("owner", "home") }.isFailure)
  }
}
