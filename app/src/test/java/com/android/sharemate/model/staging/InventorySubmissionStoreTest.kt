// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import android.content.SharedPreferences
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import kotlinx.coroutines.test.StandardTestDispatcher
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
    val key = JSONArray(listOf("owner", "home")).toString()
    preferences.edit().putString(key, "not valid json").commit()
    val store = AndroidInventorySubmissionStore(app, StandardTestDispatcher(testScheduler))
    assertTrue(runCatching { store.load("owner", "home") }.isFailure)
    val other =
        InventorySubmission(
            "12345678-1234-1234-1234-123456789abc",
            "other",
            "home",
            listOf(StagedItem("milk", "Milk", 2, Long.MAX_VALUE)))
    store.save(other)
    val foreignKey = JSONArray(listOf("other", "home")).toString()
    preferences.edit().putString(key, preferences.getString(foreignKey, null)).commit()
    assertTrue(runCatching { store.load("owner", "home") }.isFailure)
    assertEquals(other, store.load("other", "home"))
    preferences.edit().clear().commit()
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
            listOf(StagedItem("milk", "Milk", Int.MAX_VALUE, Long.MAX_VALUE)))
    store.save(saved)
    assertEquals(saved, store.load("owner", "home"))
    val preferences = app.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE)
    val key = JSONArray(listOf("owner", "home")).toString()
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
