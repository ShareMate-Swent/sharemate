// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.image.PersonalPhotoDraftError
import com.android.sharemate.model.image.PersonalPhotoDraftStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalPhotoDraftControllerTest {
  @get:Rule val temporary = TemporaryFolder()
  private val drafts = Drafts()

  private fun TestScope.controller() = PersonalPhotoDraftController(drafts, backgroundScope)

  @Test
  fun takingTheSelectedCopyTransfersOwnershipButTakingAnotherFileDoesNothing() = runTest {
    val selected = temporary.newFile()
    val other = temporary.newFile()
    drafts.onImport = { selected }
    val controller = controller()
    controller.select("content://gallery/selected")
    runCurrent()
    val generation = controller.selectionGeneration
    controller.take(other)
    assertEquals(selected, controller.state.value.draft)
    assertEquals(generation, controller.selectionGeneration)
    controller.take(selected)
    assertNull(controller.state.value.draft)
    assertEquals(generation + 1, controller.selectionGeneration)
    controller.discardDraft()
    runCurrent()
    assertTrue(selected.exists())
    assertTrue(other.exists())
    assertTrue(drafts.deleted.isEmpty())
  }

  @Test
  fun replacingASelectionDeletesOnlyItsPreviousPrivateCopy() = runTest {
    val previous = temporary.newFile()
    val replacement = temporary.newFile()
    drafts.onImport = { previous }
    val controller = controller()
    controller.select("content://gallery/previous")
    runCurrent()
    drafts.onImport = { replacement }
    controller.select("content://gallery/replacement")
    runCurrent()
    assertEquals(replacement, controller.state.value.draft)
    assertEquals(listOf(previous), drafts.deleted)
    assertFalse(previous.exists())
    assertTrue(replacement.exists())
  }

  @Test
  fun anUnexpectedProviderFailurePreservesTheChoiceAndReportsUnreadable() = runTest {
    val selected = temporary.newFile()
    drafts.onImport = { selected }
    val controller = controller()
    controller.select("content://gallery/selected")
    runCurrent()
    drafts.onImport = { throw IOException("Provider unavailable") }
    controller.select("content://gallery/unavailable")
    runCurrent()
    assertEquals(selected, controller.state.value.draft)
    assertEquals(PersonalPhotoDraftError.UNREADABLE, controller.state.value.selectionError)
    assertFalse(controller.state.value.importing)
    assertTrue(selected.exists())
  }

  @Test
  fun dismissingTheFormCleansAnImportThatReturnsAfterCancellation() = runTest {
    val lateCopy = temporary.newFile()
    val release = CompletableDeferred<Unit>()
    drafts.onImport = {
      withContext(NonCancellable) {
        release.await()
        lateCopy
      }
    }
    val controller = controller()
    controller.select("content://gallery/pending")
    runCurrent()
    assertTrue(controller.state.value.importing)
    controller.discardDraft()
    release.complete(Unit)
    runCurrent()
    assertEquals(PersonalPhotoDraftState(), controller.state.value)
    assertEquals(listOf(lateCopy), drafts.deleted)
    assertFalse(lateCopy.exists())
  }

  @Test
  fun anImportFinishingAfterOwnershipTransferCannotReplaceTheDetachedChoice() = runTest {
    val selected = temporary.newFile()
    val lateCopy = temporary.newFile()
    val release = CompletableDeferred<Unit>()
    val controller = controller()
    drafts.onImport = { selected }
    controller.select("content://gallery/selected")
    runCurrent()
    drafts.onImport = {
      release.await()
      lateCopy
    }
    controller.select("content://gallery/pending")
    runCurrent()
    controller.take(selected)
    release.complete(Unit)
    runCurrent()
    assertEquals(PersonalPhotoDraftState(), controller.state.value)
    assertEquals(listOf(lateCopy), drafts.deleted)
    assertTrue(selected.exists())
    assertFalse(lateCopy.exists())
  }

  @Test
  fun aLateProviderFailureCannotOverwriteTheNewSelection() = runTest {
    val selected = temporary.newFile()
    val release = CompletableDeferred<Unit>()
    drafts.onImport = {
      withContext(NonCancellable) { release.await() }
      throw IOException("Old provider failed")
    }
    val controller = controller()
    controller.select("content://gallery/old")
    runCurrent()
    drafts.onImport = { selected }
    controller.select("content://gallery/new")
    runCurrent()
    release.complete(Unit)
    runCurrent()
    assertEquals(PersonalPhotoDraftState(draft = selected), controller.state.value)
    assertTrue(drafts.deleted.isEmpty())
  }

  @Test
  fun closingCancelsTheImportAndCleansBothTheSelectedAndLateCopies() = runTest {
    val selected = temporary.newFile()
    val lateCopy = temporary.newFile()
    val release = CompletableDeferred<Unit>()
    val controller = controller()
    drafts.onImport = { selected }
    controller.select("content://gallery/selected")
    runCurrent()
    drafts.onImport = {
      withContext(NonCancellable) {
        release.await()
        lateCopy
      }
    }
    controller.select("content://gallery/pending")
    runCurrent()
    controller.close()
    release.complete(Unit)
    runCurrent()
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { repeat(2) { drafts.deletions.receive() } }
    }
    assertEquals(setOf(selected, lateCopy), drafts.deleted.toSet())
    assertEquals(PersonalPhotoDraftState(), controller.state.value)
    assertFalse(selected.exists())
    assertFalse(lateCopy.exists())
  }

  private class Drafts : PersonalPhotoDraftStore {
    lateinit var onImport: suspend (String) -> File
    val deleted = CopyOnWriteArrayList<File>()
    val deletions = Channel<File>(Channel.UNLIMITED)

    override suspend fun importPhoto(uri: String): File = onImport(uri)

    override suspend fun delete(file: File) {
      file.delete()
      deleted += file
      deletions.trySend(file)
    }
  }
}
