// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingFridgeActivityStoreTest {
  @Test
  fun saveRemoveAndRecreatePreservesScopedActivities() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidPendingFridgeActivityStore(app)
    val activity =
        PendingFridgeActivity(
            id = "milk:EATEN",
            householdId = "home",
            userId = "user",
            type = MessageType.EATEN,
            itemName = "Milk",
        )

    store.save(activity)
    assertEquals(listOf(activity), AndroidPendingFridgeActivityStore(app).list("home", "user"))
    assertTrue(store.list("other", "user").isEmpty())

    store.save(activity.copy(itemName = "Fresh milk"))
    assertEquals("Fresh milk", store.list("home", "user").single().itemName)
    store.remove(activity)
    assertTrue(store.list("home", "user").isEmpty())

    app.getSharedPreferences("pending_fridge_activities", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
  }

  @Test
  fun malformedStoredJsonIsSkippedWithoutReturningForeignActivities() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidPendingFridgeActivityStore(app)
    val foreign =
        PendingFridgeActivity(
            id = "bread:THROWN",
            householdId = "other",
            userId = "user",
            type = MessageType.THROWN,
            itemName = "Bread",
        )

    store.save(foreign)
    val preferences = app.getSharedPreferences("pending_fridge_activities", Context.MODE_PRIVATE)
    preferences.edit().putString("scope:other:user", "not json").commit()

    assertTrue(store.list("other", "user").isEmpty())
    assertTrue(store.list("home", "user").isEmpty())
    preferences.edit().clear().commit()
  }

  @Test
  fun malformedActivityEntriesAndUnknownTypesAreIgnored() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidPendingFridgeActivityStore(app)
    val preferences = app.getSharedPreferences("pending_fridge_activities", Context.MODE_PRIVATE)
    preferences
        .edit()
        .putString(
            "scope:home:user",
            """[
              null,
              {"id":"","householdId":"home","userId":"user","type":"EATEN","itemName":"Milk"},
              {"id":"milk:EATEN","householdId":"other","userId":"user","type":"EATEN","itemName":"Milk"},
              {"id":"milk:EATEN","householdId":"home","userId":"other","type":"EATEN","itemName":"Milk"},
              {"id":"milk:EATEN","householdId":"home","userId":"user","type":"UNKNOWN","itemName":"Milk"},
              {"id":"milk:EATEN","householdId":"home","userId":"user","type":"EATEN","itemName":" "}
            ]""",
        )
        .commit()

    assertTrue(store.list("home", "user").isEmpty())
    preferences.edit().clear().commit()
  }
}
