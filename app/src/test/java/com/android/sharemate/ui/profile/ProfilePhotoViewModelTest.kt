// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Checks that [ProfilePhotoViewModel] previews, uploads or skips the picked photo, ignores actions
 * while uploading or once done, and never leaks a private copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()
  private val events = mutableListOf<String>()
  private val files = FakeFiles(events)
  private val repository = FakeRepository(events)
  private lateinit var vm: ProfilePhotoViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    vm = ProfilePhotoViewModel(repository, files, UID)
    store.put("profile-photo", vm)
  }

  @After
  fun tearDown() {
    store.clear()
    dispatcher.scheduler.advanceUntilIdle()
    Dispatchers.resetMain()
  }

  @Test
  fun selectShowsPreviewAndDeletesOnlyThePreviousCopy() = runTest {
    vm.select("content://a", fromCamera = false)
    assertEquals(File("copy-a"), vm.state.value.photo)
    assertTrue(files.deleted.isEmpty())
    vm.select("file://b", fromCamera = true)
    assertEquals(File("copy-b"), vm.state.value.photo)
    assertEquals(listOf(File("copy-a")), files.deleted)
    assertEquals(listOf("content://a" to false, "file://b" to true), files.copies)
  }

  @Test
  fun galleryPickIsCopiedAsAGalleryImageAndShown() = runTest {
    vm.selectFromGallery("content://gallery")
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(File("copy-gallery"), vm.state.value.photo)
    assertEquals(listOf("content://gallery" to false), files.copies)
  }

  @Test
  fun olderGalleryImportFinishingLastIsDeletedAndTheLatestKept() = runTest {
    val slow = CompletableDeferred<Unit>()
    files.gates["content://a"] = slow
    vm.selectFromGallery("content://a")
    vm.selectFromGallery("content://b")
    advanceUntilIdle()
    assertEquals(File("copy-b"), vm.state.value.photo)
    slow.complete(Unit)
    advanceUntilIdle()
    assertEquals(File("copy-b"), vm.state.value.photo)
    assertEquals(listOf(File("copy-a")), files.deleted)
  }

  @Test
  fun olderImportFailingLastShowsNoError() = runTest {
    val slow = CompletableDeferred<Unit>()
    files.gates["content://a"] = slow
    vm.selectFromGallery("content://a")
    vm.selectFromGallery("content://b")
    advanceUntilIdle()
    files.failure = IllegalArgumentException("too large")
    slow.complete(Unit)
    advanceUntilIdle()
    assertNull(vm.state.value.error)
    assertEquals(File("copy-b"), vm.state.value.photo)
  }

  @Test
  fun invalidPhotoKeepsThePreviousOneUntilAValidOneIsPicked() = runTest {
    vm.select("content://a", false)
    files.failure = IllegalArgumentException("too large")
    vm.select("content://b", false)
    assertEquals(ProfilePhotoError.INVALID_PHOTO, vm.state.value.error)
    assertEquals(File("copy-a"), vm.state.value.photo)
    assertTrue(files.deleted.isEmpty())
    files.failure = null
    vm.select("content://c", false)
    assertNull(vm.state.value.error)
    assertEquals(File("copy-c"), vm.state.value.photo)
  }

  @Test
  fun saveUploadsTheCopyForTheSignedUpUserThenFinishes() = runTest {
    vm.select("content://a", false)
    vm.save()
    assertTrue(vm.state.value.isUploading)
    advanceUntilIdle()
    assertEquals(listOf(UID to File("copy-a")), repository.uploads)
    assertEquals(ProfilePhotoUiState(done = true), vm.state.value)
    assertEquals(listOf(File("copy-a")), files.deleted)
  }

  @Test
  fun failedUploadKeepsThePhotoAndRetrySucceeds() = runTest {
    vm.select("content://a", false)
    val failures =
        listOf<suspend () -> Result<String>>(
            { Result.failure(IllegalStateException("other user")) }, { error("offline") })
    for (failure in failures) {
      repository.next = failure
      vm.save()
      advanceUntilIdle()
      assertEquals(
          ProfilePhotoUiState(photo = File("copy-a"), error = ProfilePhotoError.UPLOAD_FAILED),
          vm.state.value)
      assertTrue(files.deleted.isEmpty())
    }
    repository.next = { Result.success("url") }
    vm.save()
    advanceUntilIdle()
    assertEquals(3, repository.uploads.size)
    assertTrue(vm.state.value.done)
    assertEquals(listOf(File("copy-a")), files.deleted)
  }

  @Test
  fun skipFinishesAndDeletesThePickedCopy() = runTest {
    vm.select("content://a", false)
    vm.skip()
    advanceUntilIdle()
    assertEquals(ProfilePhotoUiState(done = true), vm.state.value)
    assertEquals(listOf(File("copy-a")), files.deleted)
    assertTrue(repository.uploads.isEmpty())
  }

  @Test
  fun pickAndSaveAreIgnoredWhileUploadingAndOnceDone() = runTest {
    vm.select("content://a", false)
    val upload = CompletableDeferred<Result<String>>()
    repository.next = { upload.await() }
    vm.save()
    advanceUntilIdle()
    vm.select("content://b", false)
    vm.save()
    advanceUntilIdle()
    assertEquals(1, files.copies.size)
    assertEquals(1, repository.uploads.size)
    assertTrue(vm.state.value.isUploading)
    assertFalse(vm.state.value.done)
    upload.complete(Result.success("url"))
    advanceUntilIdle()
    assertTrue(vm.state.value.done)
    vm.select("content://c", false)
    vm.save()
    advanceUntilIdle()
    assertEquals(1, files.copies.size)
    assertEquals(1, repository.uploads.size)
    assertNull(vm.state.value.photo)
  }

  @Test
  fun skipDuringUploadCancelsItThenDeletesTheCopy() = runTest {
    vm.select("content://a", false)
    repository.next = { CompletableDeferred<Result<String>>().await() }
    vm.save()
    advanceUntilIdle()
    vm.skip()
    advanceUntilIdle()
    assertEquals(ProfilePhotoUiState(done = true), vm.state.value)
    assertEquals(listOf("upload cancelled", "delete copy-a"), events)
  }

  @Test
  fun copyFinishingAfterClearIsDeleted() = runTest {
    files.gate = CompletableDeferred()
    launch { vm.select("content://late", false) }
    advanceUntilIdle()
    store.clear()
    files.gate!!.complete(Unit)
    advanceUntilIdle()
    assertNull(vm.state.value.photo)
    assertEquals(listOf(File("copy-late")), files.deleted)
  }

  @Test
  fun copyFinishingDuringUploadIsDeletedAndThePreviewKept() = runTest {
    vm.select("content://a", false)
    files.gate = CompletableDeferred()
    launch { vm.select("content://late", false) }
    advanceUntilIdle()
    repository.next = { CompletableDeferred<Result<String>>().await() }
    vm.save()
    files.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(File("copy-a"), vm.state.value.photo)
    assertEquals(listOf(File("copy-late")), files.deleted)
  }

  @Test
  fun cancellationOfTheCopyIsRethrownWithoutAnError() = runTest {
    files.failure = CancellationException("picker closed")
    try {
      vm.select("content://a", false)
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {}
    assertEquals(ProfilePhotoUiState(), vm.state.value)
  }

  @Test
  fun clearingCancelsThePendingUploadBeforeDeletingTheCopy() = runTest {
    vm.select("content://a", false)
    repository.next = { CompletableDeferred<Result<String>>().await() }
    vm.save()
    advanceUntilIdle()
    store.clear()
    advanceUntilIdle()
    assertEquals(listOf("upload cancelled", "delete copy-a"), events)
  }

  @Test
  fun clearingDeletesAnUnsavedCopy() = runTest {
    vm.select("content://a", false)
    store.clear()
    advanceUntilIdle()
    assertEquals(listOf(File("copy-a")), files.deleted)
  }

  /**
   * Copies `scheme://name` to `copy-name`; deletions are recorded, never performed. A copy waits
   * for [gate] and for its own entry in [gates], if any.
   */
  private class FakeFiles(private val events: MutableList<String>) : PersonalPhotoFiles {
    val copies = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<File>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun copy(uri: String, fromCamera: Boolean): File {
      copies += uri to fromCamera
      gate?.await()
      gates[uri]?.await()
      failure?.let { throw it }
      return File("copy-" + uri.substringAfter("://"))
    }

    override suspend fun delete(file: File) {
      events += "delete ${file.name}"
      deleted += file
    }
  }

  private class FakeRepository(private val events: MutableList<String>) : ProfilePhotoRepository {
    val uploads = mutableListOf<Pair<String, File>>()
    var next: suspend () -> Result<String> = { Result.success("url") }

    override suspend fun upload(expectedUid: String, source: File): Result<String> {
      uploads += expectedUid to source
      try {
        return next()
      } catch (cancelled: CancellationException) {
        events += "upload cancelled"
        throw cancelled
      }
    }
  }

  private companion object {
    const val UID = "new-user"
  }
}
