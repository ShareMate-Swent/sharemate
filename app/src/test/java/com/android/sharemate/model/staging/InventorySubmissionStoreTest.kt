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
  fun privateCorrectionAndAcknowledgementSurviveJournalRestart() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = java.util.UUID.randomUUID().toString()
    val original =
        InventorySubmission(
            id,
            "alice",
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100)),
            sharing = com.android.sharemate.model.item.ItemSharing.PERSONAL)
    val replacement =
        InventorySubmission(
            id,
            "alice",
            "home",
            original.items + StagedItem("bread", "Bread", 1, 300),
            sharing = com.android.sharemate.model.item.ItemSharing.PERSONAL)
    store.save(original)
    store.saveDetails(ReceiptDetails(id, "alice", "home", "photo", 1, savedAtMillis = 2))
    val correction = ReceiptCorrection("private-edit", original, replacement, "Private edited")
    store.saveCorrection(correction)
    val restarted = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(correction, restarted.pendingCorrection("alice", "home", id))
    restarted.acknowledgeCorrection(correction)
    val verified = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(replacement, verified.loadById("alice", "home", id))
    assertEquals("Private edited", verified.details("alice", "home", id)?.title)
    assertEquals(1L, verified.details("alice", "home", id)?.revision)
    assertNull(verified.pendingCorrection("alice", "home", id))
  }

  @Test
  fun sharingModeSurvivesJournalRestartAndLegacySharedEntries() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    for (mode in com.android.sharemate.model.item.ItemSharing.entries) {
      val receipt =
          InventorySubmission(
              java.util.UUID.randomUUID().toString(),
              "alice",
              "home",
              listOf(StagedItem("milk", "Milk", 1, 100)),
              sharing = mode)
      store.save(receipt)
      val restored =
          AndroidInventorySubmissionStore(app, dispatcher).loadById("alice", "home", receipt.id)
      assertEquals(receipt, restored)
      val legacy = store.encode(receipt).apply { remove(Fields.SHARING) }
      assertEquals(
          com.android.sharemate.model.item.ItemSharing.SHARED,
          store.decode(legacy.toString(), "alice", "home").sharing)
    }
  }

  @Test
  fun sharedCacheSurvivesRestartAndNeverIncludesOtherUsersDrafts() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val saved =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            "alice",
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100)))
    val draft =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
    fun entry(receipt: InventorySubmission) =
        com.android.sharemate.model.receipt.ReceiptCloudEntry(
            receipt,
            ReceiptDetails(
                receipt.id, receipt.ownerId, receipt.householdId, "photo", 1, title = "Courses"))
    store.cacheShared("bob", "home", listOf(entry(saved), entry(draft)))
    val restored = AndroidInventorySubmissionStore(app, dispatcher, viewerId = "bob")
    assertEquals(listOf(saved), restored.sharedReceipts("bob", "home"))
    assertEquals("Courses", restored.details("alice", "home", saved.id)?.title)
    assertTrue(restored.sharedReceipts("outsider", "home").isEmpty())
    assertTrue(restored.sharedReceipts("bob", "other-home").isEmpty())
    assertNull(restored.loadById("alice", "home", draft.id))
    restored.cacheShared("bob", "home", emptyList())
    assertTrue(restored.sharedReceipts("bob", "home").isEmpty())
  }

  @Test
  fun serverDeletionRemovesPreviouslySyncedReceiptWithoutRemovingUnpublishedDraft() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val synced =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
    val local =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
    store.save(synced)
    store.save(local)
    store.applyCloudDeletions("alice", "home", emptySet())
    assertEquals(synced, store.loadById("alice", "home", synced.id))
    store.applyCloudDeletions("alice", "home", setOf(synced.id))
    assertNull(store.loadById("alice", "home", synced.id))
    assertEquals(local, store.loadById("alice", "home", local.id))
    assertTrue(store.isDeleted("alice", "home", synced.id))
    try {
      store.save(synced)
      fail("Stale snapshot must not resurrect deleted receipt")
    } catch (_: IllegalStateException) {}
  }

  @Test
  fun deletingSavedReceiptRemovesMetadataAndPreservesOtherReceipts() = runTest {
    val store =
        AndroidInventorySubmissionStore(
            RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))
    val rows = listOf(StagedItem("a", "Milk", 1, 100))
    store.save(
        InventorySubmission("00000000-0000-0000-0000-000000000001", "delete-owner", "home", rows))
    store.saveDetails(
        ReceiptDetails("00000000-0000-0000-0000-000000000001", "delete-owner", "home", "photo", 1))
    store.save(
        InventorySubmission(
            "00000000-0000-0000-0000-000000000002", "delete-owner", "home", rows, draft = true))
    store.deleteReceipt("delete-owner", "home", "00000000-0000-0000-0000-000000000001")
    assertNull(store.loadById("delete-owner", "home", "00000000-0000-0000-0000-000000000001"))
    assertNull(store.details("delete-owner", "home", "00000000-0000-0000-0000-000000000001"))
    assertEquals(
        listOf("00000000-0000-0000-0000-000000000002"),
        store.list("delete-owner", "home").map { it.id })
    store.deleteReceipt("delete-owner", "home", "00000000-0000-0000-0000-000000000001")
  }

  @Test
  fun correctionJournalSurvivesRestartAndIsScopedToReceiptAndAccount() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Milk", 1, 100))
    val original = InventorySubmission(id, "owner", "home", rows)
    val replacement =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows,
            expense =
                com.android.sharemate.model.finance.SharedExpense(
                    id, "home", "owner", 100, mapOf("owner" to 100L)))
    val correction =
        ReceiptCorrection(java.util.UUID.randomUUID().toString(), original, replacement, "Courses")
    store.saveCorrection(correction)
    val restored = AndroidInventorySubmissionStore(app, dispatcher)
    assertEquals(correction, restored.pendingCorrection("owner", "home", id))
    assertNull(restored.pendingCorrection("other", "home", id))
    assertNull(restored.pendingCorrection("owner", "other", id))
    restored.clearCorrection("owner", "home", id)
    assertNull(store.pendingCorrection("owner", "home", id))
  }

  @Test
  fun receiptTitleSurvivesStoreRecreationAndOldMetadataHasNoTitle() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = java.util.UUID.randomUUID().toString()
    store.save(
        InventorySubmission(
            id, "owner", "home", listOf(StagedItem("a", "Milk", 1, 100)), draft = true))
    val details =
        ReceiptDetails(
            id,
            "owner",
            "home",
            "photo",
            42L,
            title = "Courses Migros",
            draftPaidBy = "bob",
            draftParticipants = setOf("owner", "bob"))
    store.saveDetails(details)
    assertEquals(
        details, AndroidInventorySubmissionStore(app, dispatcher).details("owner", "home", id))
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val key = "details:${JSONArray(listOf("owner", "home"))}:$id"
    val legacy = JSONObject(checkNotNull(preferences.getString(key, null)))
    legacy.remove("title")
    preferences.edit().putString(key, legacy.toString()).commit()
    assertEquals(
        "", AndroidInventorySubmissionStore(app, dispatcher).details("owner", "home", id)?.title)
  }

  @Test
  fun delayedDraftCannotReplacePurchaseRecordedByAnotherMember() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher)
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("row", "Milk", 1, 100))
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "bob", 100, mapOf("alice" to 100L))
    val queued = InventorySubmission(id, "alice", "home", rows, expense = expense)
    store.save(queued)
    val staleEditor = AndroidInventorySubmissionStore(app, dispatcher)
    assertTrue(
        runCatching {
              staleEditor.save(InventorySubmission(id, "alice", "home", rows, draft = true))
            }
            .isFailure)
    assertEquals(queued, store.loadById("alice", "home", id))
  }

  @Test
  fun purchaseStoreRestoresLegacyPendingReceiptAndPreservesItsIdentity() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val receipt =
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            "migration-owner",
            "home",
            listOf(StagedItem("a", "Apple", 1, 100)))
    AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true).save(receipt)
    val store = AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
    assertEquals(receipt, store.load(receipt.ownerId, receipt.householdId))
    assertEquals(listOf(receipt), store.list(receipt.ownerId, receipt.householdId))
    assertEquals(
        receipt,
        AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
            .load(receipt.ownerId, receipt.householdId))
  }

  @Test
  fun missingIndexedReceiptFailsBothLoadAndList() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidInventorySubmissionStore(app, StandardTestDispatcher(testScheduler))
    val receipt =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abe",
            "missing-record-owner",
            "home",
            listOf(StagedItem("a", "Apple", 1, 100)),
            draft = true)
    store.save(receipt)
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val scope = JSONArray(listOf(receipt.ownerId, receipt.householdId))
    assertTrue(preferences.edit().remove("receipt:$scope:${receipt.id}").commit())

    assertTrue(runCatching { store.load(receipt.ownerId, receipt.householdId) }.isFailure)
    assertTrue(runCatching { store.list(receipt.ownerId, receipt.householdId) }.isFailure)
    assertTrue(
        runCatching { store.loadById(receipt.ownerId, receipt.householdId, receipt.id) }.isFailure)
  }

  @Test
  fun legacyArchiveReleasesActiveSlotAndPreservesRejectedOperation() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
    val id = "12345678-1234-1234-1234-123456789abf"
    val rejected =
        InventorySubmission(
            id,
            "legacy-archive-owner",
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100)),
            expense =
                com.android.sharemate.model.finance.SharedExpense(
                    id, "home", "legacy-archive-owner", 100, mapOf("legacy-archive-owner" to 100L)),
            inventoryConfirmed = true,
            expenseProgress = com.android.sharemate.model.item.ExpenseProgress.REJECTED)
    store.save(rejected)
    store.setAside(rejected)

    val restored = AndroidInventorySubmissionStore(app, dispatcher, legacyActive = true)
    assertNull(restored.load(rejected.ownerId, rejected.householdId))
    assertEquals(listOf(rejected), restored.deferred(rejected.ownerId, rejected.householdId))
    assertTrue(runCatching { restored.setAside(rejected) }.isFailure)
  }

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
            { it.getJSONObject(Fields.EXPENSE).put("paidBy", "") },
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
        InventorySubmission(
            java.util.UUID.randomUUID().toString(),
            saved.ownerId,
            saved.householdId,
            saved.items,
            draft = true)
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
