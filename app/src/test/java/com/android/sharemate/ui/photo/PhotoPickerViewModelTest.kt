// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.image.TemporaryPhotoStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoPickerViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = PhotoStore()
  private val saved = SavedStateHandle()
  private lateinit var vm: PhotoPickerViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    vm = PhotoPickerViewModel(store, saved)
    vm.open()
  }

  @After fun tearDown() = Dispatchers.resetMain()

  @Test
  fun photoOriginSurvivesRestorationAndOnlyChangesAfterSuccessfulReplacement() = runTest {
    vm.capture {}
    advanceUntilIdle()
    assertEquals(PhotoSource.CAMERA, vm.state.value.selection?.source)
    assertEquals(true, saved.get<Boolean>(PhotoPickerViewModel.CAMERA_KEY))

    val restored = PhotoPickerViewModel(store, saved)
    advanceUntilIdle()
    assertEquals(PhotoSource.CAMERA, restored.state.value.selection?.source)
    restored.open()
    restored.select(null)
    store.importFailure = IOException("Gallery unavailable")
    restored.select("content://gallery/broken")
    advanceUntilIdle()
    assertEquals(PhotoSource.CAMERA, restored.state.value.selection?.source)
    assertEquals(true, saved.get<Boolean>(PhotoPickerViewModel.CAMERA_KEY))

    store.importFailure = null
    restored.select("content://gallery/replacement")
    advanceUntilIdle()
    assertEquals(PhotoSource.GALLERY, restored.state.value.selection?.source)
    assertEquals(false, saved.get<Boolean>(PhotoPickerViewModel.CAMERA_KEY))
    val galleryRestored = PhotoPickerViewModel(store, saved)
    advanceUntilIdle()
    assertEquals(PhotoSource.GALLERY, galleryRestored.state.value.selection?.source)
    galleryRestored.capture { throw IOException("Camera unavailable") }
    advanceUntilIdle()
    assertEquals(PhotoSource.GALLERY, galleryRestored.state.value.selection?.source)
    galleryRestored.reset()
    advanceUntilIdle()
    assertNull(saved.get<Boolean>(PhotoPickerViewModel.CAMERA_KEY))
    assertNull(galleryRestored.state.value.selection)
  }

  @Test
  fun cancellingPickerPreservesPreviousImageWithoutFailure() = runTest {
    vm.open()
    vm.select("content://gallery/first")
    advanceUntilIdle()
    val previous = vm.state.value
    vm.select(null)
    advanceUntilIdle()
    assertEquals(previous, vm.state.value)
    assertEquals(listOf("content://gallery/first"), store.imports)
    assertEquals(PhotoPage.PREVIEW, previous.page)
    assertFalse(previous.failed)
  }

  @Test
  fun pendingImportRejectsConcurrentSelectionAndOnlyDeletesReplacedPrivateImage() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    store.pendingImport = CompletableDeferred()
    vm.select("content://gallery/second")
    vm.select("content://gallery/third")
    vm.confirm { fail("Cannot confirm during import") }
    advanceUntilIdle()
    assertTrue(vm.state.value.busy)
    assertEquals(listOf("content://gallery/first", "content://gallery/second"), store.imports)
    assertTrue(store.deleted.isEmpty())
    store.pendingImport!!.complete(Unit)
    advanceUntilIdle()
    assertEquals("file:///private/second", vm.state.value.selection?.uri)
    assertEquals(listOf("file:///private/first"), store.deleted)
    assertEquals(vm.state.value.selection?.uri, saved.get<String>(PhotoPickerViewModel.IMAGE_KEY))
  }

  @Test
  fun importFailurePreservesPreviousImageAndAllowsRetry() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    store.importFailure = IOException("private path")
    vm.select("content://gallery/broken")
    advanceUntilIdle()
    assertEquals("file:///private/first", vm.state.value.selection?.uri)
    assertTrue(vm.state.value.failed)
    assertFalse(vm.state.value.busy)
    assertTrue(store.deleted.isEmpty())
    store.importFailure = null
    vm.select("content://gallery/retry")
    advanceUntilIdle()
    assertFalse(vm.state.value.failed)
    assertEquals("file:///private/retry", vm.state.value.selection?.uri)
  }

  @Test
  fun captureFailureDeletesTemporaryFileAndAllowsAnotherCapture() = runTest {
    vm.open()
    vm.showCamera()
    vm.capture { throw IOException("camera disconnected") }
    advanceUntilIdle()
    assertNull(vm.state.value.selection?.uri)
    assertTrue(vm.state.value.failed)
    assertEquals(listOf("file:///private/capture"), store.deleted)
    vm.capture { assertEquals("file:///private/capture", it) }
    advanceUntilIdle()
    assertEquals(PhotoPage.PREVIEW, vm.state.value.page)
    assertFalse(vm.state.value.failed)
    assertEquals("file:///private/capture", vm.state.value.selection?.uri)
  }

  @Test
  fun confirmationWaitsForConsumerAndCannotSubmitOrDeleteImageTwice() = runTest {
    vm.open()
    vm.select("content://gallery/first")
    advanceUntilIdle()
    val processing = CompletableDeferred<Unit>()
    val consumed = mutableListOf<SelectedPhoto>()
    vm.confirm {
      consumed += it
      processing.await()
    }
    advanceUntilIdle()
    vm.confirm { fail("Duplicate confirmation") }
    vm.reset()
    vm.select("content://gallery/replacement")
    assertTrue(vm.state.value.busy)
    assertTrue(vm.state.value.visible)
    assertFalse(vm.state.value.confirmed)
    assertTrue(store.deleted.isEmpty())
    processing.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(SelectedPhoto("file:///private/first", PhotoSource.GALLERY)), consumed)
    assertTrue(vm.state.value.confirmed)
    assertFalse(vm.state.value.visible)
    assertFalse(vm.state.value.busy)
    vm.open()
    vm.confirm { fail("Already confirmed image must not be resubmitted") }
    advanceUntilIdle()
    val deleted = CompletableDeferred<Unit>()
    store.afterDelete = { deleted.complete(Unit) }
    vm.reset()
    advanceUntilIdle()
    deleted.await()
    assertEquals(listOf("file:///private/first"), store.deleted)
    assertNull(saved.get<String>(PhotoPickerViewModel.IMAGE_KEY))
  }

  @Test
  fun failedConsumerKeepsPreviewAndCanBeRetried() = runTest {
    vm.open()
    vm.select("content://gallery/first")
    advanceUntilIdle()
    vm.confirm { throw IOException("decoder failed") }
    advanceUntilIdle()
    assertTrue(vm.state.value.failed)
    assertTrue(vm.state.value.confirmationFailed)
    assertTrue(vm.state.value.visible)
    assertFalse(vm.state.value.confirmed)
    assertTrue(store.deleted.isEmpty())
    vm.confirm { assertEquals(SelectedPhoto("file:///private/first", PhotoSource.GALLERY), it) }
    advanceUntilIdle()
    assertTrue(vm.state.value.confirmed)
    assertFalse(vm.state.value.failed)
    assertFalse(vm.state.value.confirmationFailed)
  }

  @Test
  fun replacementFailureDoesNotKeepPreviousConsumerError() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    vm.confirm { throw IOException("lookup failed") }
    advanceUntilIdle()
    assertTrue(vm.state.value.confirmationFailed)
    store.importFailure = IOException("unreadable replacement")
    vm.select("content://gallery/broken")
    advanceUntilIdle()
    assertTrue(vm.state.value.failed)
    assertFalse(vm.state.value.confirmationFailed)
    assertEquals("file:///private/first", vm.state.value.selection?.uri)
  }

  @Test
  fun failedReplacementDoesNotResubmitAlreadyConfirmedImage() = runTest {
    val consumed = mutableListOf<SelectedPhoto>()
    vm.select("content://gallery/first")
    advanceUntilIdle()
    vm.confirm { consumed += it }
    advanceUntilIdle()
    vm.open()
    store.importFailure = IOException("new photo unavailable")
    vm.select("content://gallery/replacement")
    advanceUntilIdle()
    assertEquals("file:///private/first", vm.state.value.selection?.uri)
    vm.confirm { consumed += it }
    advanceUntilIdle()
    assertEquals(listOf(SelectedPhoto("file:///private/first", PhotoSource.GALLERY)), consumed)
  }

  @Test
  fun cancelledCaptureCleansItsFileWithoutDisplayingFailure() = runTest {
    vm.capture { throw CancellationException("screen disposed") }
    advanceUntilIdle()
    assertFalse(vm.state.value.failed)
    assertFalse(vm.state.value.busy)
    assertNull(vm.state.value.selection?.uri)
    assertEquals(listOf("file:///private/capture"), store.deleted)
  }

  @Test
  fun restorationRevalidatesImageAndMissingImageCanBeReplaced() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    vm.confirm()
    advanceUntilIdle()
    val restored = PhotoPickerViewModel(store, saved)
    advanceUntilIdle()
    assertEquals("file:///private/first", restored.state.value.selection?.uri)
    assertTrue(restored.state.value.confirmed)
    assertTrue(store.validated.contains("file:///private/first"))
    store.validationFailure = IOException("removed by OS")
    val missing = PhotoPickerViewModel(store, saved)
    advanceUntilIdle()
    assertTrue(missing.state.value.failed)
    store.validationFailure = null
    missing.open()
    missing.select("content://gallery/recovered")
    advanceUntilIdle()
    assertEquals("file:///private/recovered", missing.state.value.selection?.uri)
    assertFalse(missing.state.value.failed)
    assertFalse(missing.state.value.confirmed)
  }

  @Test
  fun leavingFormWaitsForConsumerCleanupAndIgnoresLateGalleryResult() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    val finishing = CompletableDeferred<Unit>()
    val deleted = CompletableDeferred<Unit>()
    store.afterDelete = { deleted.complete(Unit) }
    vm.confirm { withContext(NonCancellable) { finishing.await() } }
    advanceUntilIdle()
    vm.discard()
    advanceUntilIdle()
    assertFalse(vm.state.value.visible)
    assertTrue(store.deleted.isEmpty())
    vm.select("content://gallery/late")
    finishing.complete(Unit)
    advanceUntilIdle()
    deleted.await()
    assertEquals(listOf("content://gallery/first"), store.imports)
    assertNull(vm.state.value.selection?.uri)
    assertFalse(vm.state.value.confirmed)
    assertEquals(listOf("file:///private/first"), store.deleted)
  }

  @Test
  fun closedFormIgnoresCaptureAndConfirmationButCanBeReopened() = runTest {
    vm.select("content://gallery/first")
    advanceUntilIdle()
    vm.closePreview()
    val closed = vm.state.value
    val validations = store.validated.toList()
    vm.capture { fail("Closed form must not start the camera") }
    vm.confirm { fail("Closed form must not hand off an image") }
    advanceUntilIdle()
    assertEquals(closed, vm.state.value)
    assertEquals(0, store.captures)
    assertEquals(validations, store.validated)
    assertTrue(store.deleted.isEmpty())
    assertEquals(false, saved.get<Boolean>(PhotoPickerViewModel.VISIBLE_KEY))
    vm.open()
    vm.capture { assertEquals("file:///private/capture", it) }
    advanceUntilIdle()
    val consumed = mutableListOf<SelectedPhoto>()
    vm.confirm { consumed += it }
    advanceUntilIdle()
    assertEquals(1, store.captures)
    assertEquals(listOf(SelectedPhoto("file:///private/capture", PhotoSource.CAMERA)), consumed)
    assertTrue(vm.state.value.confirmed)
  }

  @Test
  fun clearingViewModelWaitsForCaptureCallbackBeforeDeletingItsFile() = runTest {
    val owner = ViewModelStore().apply { put("photo", vm) }
    val finishing = CompletableDeferred<Unit>()
    var callbackFinished = false
    vm.capture {
      withContext(NonCancellable) {
        finishing.await()
        callbackFinished = true
      }
    }
    advanceUntilIdle()
    assertEquals(1, store.captures)
    store.afterDelete = { assertTrue(callbackFinished) }
    owner.clear()
    advanceUntilIdle()
    assertFalse(callbackFinished)
    assertTrue(store.deleted.isEmpty())
    finishing.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf("file:///private/capture"), store.deleted)
    assertFalse(vm.state.value.failed)
  }

  @Test
  fun clearingViewModelWaitsForCancelledConsumerCleanupBeforeDeletingItsFile() = runTest {
    val owner = ViewModelStore().apply { put("photo", vm) }
    vm.select("content://gallery/first")
    advanceUntilIdle()
    val finishing = CompletableDeferred<Unit>()
    var cleanupStarted = false
    var cleanupFinished = false
    vm.confirm {
      try {
        CompletableDeferred<Unit>().await()
      } finally {
        cleanupStarted = true
        withContext(NonCancellable) {
          finishing.await()
          cleanupFinished = true
        }
      }
    }
    advanceUntilIdle()
    store.afterDelete = { assertTrue(cleanupFinished) }
    owner.clear()
    advanceUntilIdle()
    assertTrue(cleanupStarted)
    assertFalse(cleanupFinished)
    assertTrue(store.deleted.isEmpty())
    finishing.complete(Unit)
    advanceUntilIdle()
    assertTrue(cleanupFinished)
    assertEquals(listOf("file:///private/first"), store.deleted)
    assertFalse(vm.state.value.confirmed)
    assertFalse(vm.state.value.failed)
  }

  private class PhotoStore : TemporaryPhotoStore {
    var captures = 0
    val imports = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val validated = mutableListOf<String>()
    var pendingImport: CompletableDeferred<Unit>? = null
    var importFailure: Exception? = null
    var validationFailure: Exception? = null
    var afterDelete: () -> Unit = {}

    override suspend fun createCapture(): String {
      captures++
      return "file:///private/capture"
    }

    override suspend fun importImage(uri: String): String {
      imports += uri
      pendingImport?.await()
      importFailure?.let { throw it }
      return "file:///private/${uri.substringAfterLast('/')}"
    }

    override suspend fun validate(uri: String) {
      validated += uri
      validationFailure?.let { throw it }
    }

    override suspend fun delete(uri: String) {
      deleted += uri
      afterDelete()
    }
  }
}
