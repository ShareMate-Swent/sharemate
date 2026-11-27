// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidDeletedHouseholdStoreTest {
  private val app = RuntimeEnvironment.getApplication()

  @Test
  fun aRecordedHouseholdIsDeletedForThatAccountOnly() = runTest {
    val store = AndroidDeletedHouseholdStore(app, Dispatchers.Unconfined)

    store.markDeleted("alice", "home")

    assertTrue(store.isDeleted("alice", "home"))
    assertFalse(store.isDeleted("alice", "other-home"))
    assertFalse(store.isDeleted("bob", "home"))
  }

  @Test
  fun theRecordSurvivesARestartOfTheApp() = runTest {
    AndroidDeletedHouseholdStore(app, Dispatchers.Unconfined).markDeleted("alice", "home")

    assertTrue(AndroidDeletedHouseholdStore(app, Dispatchers.Unconfined).isDeleted("alice", "home"))
  }

  @Test
  fun severalHouseholdsAreKeptForOneAccount() = runTest {
    val store = AndroidDeletedHouseholdStore(app, Dispatchers.Unconfined)

    store.markDeleted("alice", "first")
    store.markDeleted("alice", "second")
    store.markDeleted("alice", "first")

    assertTrue(store.isDeleted("alice", "first"))
    assertTrue(store.isDeleted("alice", "second"))
  }

  @Test
  fun aRecordThatCannotBeCommittedIsReportedAsAnIoError() = runTest {
    val editor = mock(SharedPreferences.Editor::class.java)
    val preferences = mock(SharedPreferences::class.java)
    val context = mock(Context::class.java)
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.getSharedPreferences("deleted_households", Context.MODE_PRIVATE))
        .thenReturn(preferences)
    `when`(preferences.edit()).thenReturn(editor)
    `when`(editor.putStringSet(anyString(), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(editor)
    `when`(editor.commit()).thenReturn(false)

    val failure = runCatching {
      AndroidDeletedHouseholdStore(context, Dispatchers.Unconfined).markDeleted("alice", "home")
    }

    assertTrue(failure.exceptionOrNull() is IOException)
  }
}
