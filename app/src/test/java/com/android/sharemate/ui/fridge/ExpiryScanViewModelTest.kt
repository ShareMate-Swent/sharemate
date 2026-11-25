// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpiryScanViewModelTest {
  private val uri = "file:///private/expiry.jpg"
  private val dates = listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2027, 10, 12))
  private val saved = SavedStateHandle()
  private val deleted = mutableListOf<String>()
  private val store =
      object : TemporaryPhotoStore {
        override suspend fun createCapture() = uri

        override suspend fun importImage(uri: String) = error("Test uses a camera capture")

        override suspend fun validate(uri: String) = Unit

        override suspend fun delete(uri: String) {
          deleted += uri
        }
      }

  @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @After fun tearDown() = Dispatchers.resetMain()

  @Test
  fun restoredPhotoCanBeRetriedAfterRecognitionFailure() = runTest {
    val picker = PhotoPickerViewModel(store, saved)
    picker.open()
    picker.capture { assertEquals(uri, it) }
    advanceUntilIdle()
    val restored = PhotoPickerViewModel(store, saved)
    advanceUntilIdle()
    var attempts = 0
    val scan = ExpiryScanViewModel {
      assertEquals(uri, it)
      if (++attempts == 1) throw IOException("Unreadable")
      dates
    }

    restored.confirm { scan.scan(it.uri) }
    advanceUntilIdle()
    assertTrue(restored.state.value.visible)
    assertTrue(restored.state.value.confirmationFailed)
    assertNull(scan.candidates.value)
    assertTrue(deleted.isEmpty())

    restored.confirm { scan.scan(it.uri) }
    advanceUntilIdle()
    assertTrue(restored.state.value.confirmed)
    assertFalse(restored.state.value.visible)
    assertEquals(dates, scan.candidates.value)
    scan.clear()
    assertNull(scan.candidates.value)
  }

  @Test
  fun emptyScanIsACompletedRead() = runTest {
    val scan = ExpiryScanViewModel { emptyList() }
    scan.scan(uri)
    assertEquals(emptyList<LocalDate>(), scan.candidates.value)
  }

  @Test
  fun leavingFormCancelsRecognitionBeforeDeletingPhoto() = runTest {
    val picker = PhotoPickerViewModel(store, saved)
    var started = false
    var cancelled = false
    val scan = ExpiryScanViewModel {
      try {
        started = true
        awaitCancellation()
      } finally {
        assertTrue(deleted.isEmpty())
        cancelled = true
      }
    }
    picker.open()
    picker.capture {}
    advanceUntilIdle()
    picker.confirm { scan.scan(it.uri) }
    runCurrent()
    assertTrue(started)

    picker.discard()
    advanceUntilIdle()

    assertTrue(cancelled)
    assertEquals(listOf(uri), deleted)
    assertNull(scan.candidates.value)
  }
}
