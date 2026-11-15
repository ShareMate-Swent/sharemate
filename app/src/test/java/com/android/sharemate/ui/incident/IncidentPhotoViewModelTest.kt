// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.incident

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.image.PersonalPhotoFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentPhotoViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val files = Files()
  private lateinit var vm: IncidentPhotoViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    vm = IncidentPhotoViewModel(files)
  }

  @After fun tearDown() = Dispatchers.resetMain()

  @Test
  fun confirmedCameraAndGalleryCopiesReplaceAndRemoveOnlyOwnedFiles() = runTest {
    vm.selectAndWait("file://capture", true)
    val first = vm.state.value.file
    vm.selectAndWait("content://gallery", false)
    val second = vm.state.value.file
    assertNotEquals(first, second)
    assertEquals(listOf("file://capture" to true, "content://gallery" to false), files.origins)
    assertEquals(listOf(first), files.deleted)
    vm.remove()
    advanceUntilIdle()
    assertEquals(IncidentPhotoState(), vm.state.value)
    assertEquals(listOf(first, second), files.deleted)
  }

  @Test
  fun failedReplacementPreservesAttachmentAndCanBeRetried() = runTest {
    vm.selectAndWait("first", false)
    val first = vm.state.value.file
    files.failure = IOException("Invalid image")
    try {
      vm.selectAndWait("broken", false)
      fail("Expected import rejection")
    } catch (_: IOException) {}
    assertEquals(IncidentPhotoState(first, rejected = true), vm.state.value)
    assertTrue(files.deleted.isEmpty())
    files.failure = null
    vm.selectAndWait("retry", false)
    assertFalse(vm.state.value.rejected)
    assertNotEquals(first, vm.state.value.file)
  }

  @Test
  fun cancellationKeepsPreviousPhotoAndDeletesAnUnacceptedCopy() = runTest {
    vm.selectAndWait("first", false)
    val first = vm.state.value.file
    val gate = CompletableDeferred<Unit>()
    files.gate = gate
    val pending = launch { vm.selectAndWait("replacement", true) }
    runCurrent()
    assertTrue(vm.state.value.importing)
    vm.remove() // Removal cannot race a replacement.
    assertEquals(first, vm.state.value.file)
    pending.cancel()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(IncidentPhotoState(first), vm.state.value)
    assertEquals(listOf(File("copy-2")), files.deleted)
  }

  @Test
  fun duplicateConfirmationIsRejectedWhileCopyIsPending() = runTest {
    val gate = CompletableDeferred<Unit>()
    files.gate = gate
    val pending = launch { vm.selectAndWait("first", false) }
    runCurrent()
    try {
      vm.selectAndWait("duplicate", false)
      fail("Expected busy guard")
    } catch (_: IllegalStateException) {}
    gate.complete(Unit)
    pending.join()
    assertEquals(1, files.origins.size)
  }

  @Test
  fun clearingDraftCancelsImportAndCleansBothConfirmedAndPendingCopies() = runTest {
    val store = ViewModelStore()
    store.put("incident", vm)
    vm.selectAndWait("first", false)
    val gate = CompletableDeferred<Unit>()
    files.gate = gate
    val pending = launch { vm.selectAndWait("pending", true) }
    runCurrent()
    store.clear()
    gate.complete(Unit)
    advanceUntilIdle()
    assertTrue(pending.isCancelled)
    assertEquals(IncidentPhotoState(), vm.state.value)
    assertEquals(setOf(File("copy-1"), File("copy-2")), files.deleted.toSet())
  }

  private class Files : PersonalPhotoFiles {
    val origins = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<File>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun copy(uri: String, fromCamera: Boolean): File {
      origins += uri to fromCamera
      failure?.let { throw it }
      val file = File("copy-${origins.size}")
      // Model a file operation which finishes after cancellation; the caller must release it.
      withContext(NonCancellable) { gate?.await() }
      return file
    }

    override suspend fun delete(file: File) {
      deleted += file
    }
  }
}
