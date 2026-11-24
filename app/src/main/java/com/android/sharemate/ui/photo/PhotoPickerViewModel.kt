// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.image.PhotoImportRejection
import com.android.sharemate.model.image.PhotoImportValidationException
import com.android.sharemate.model.image.TemporaryPhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PhotoPage {
  SOURCE,
  CAMERA,
  PREVIEW
}

enum class PhotoSource {
  CAMERA,
  GALLERY
}

/** The camera the picker captures with. */
enum class CameraLens {
  BACK,
  FRONT;

  /** The other lens, used by the switch button. */
  fun other(): CameraLens = if (this == BACK) FRONT else BACK
}

/** A private image and the source that produced it, valid during confirmation. */
data class SelectedPhoto(val uri: String, val source: PhotoSource)

data class PhotoPickerState(
    val selection: SelectedPhoto? = null,
    val visible: Boolean = false,
    val page: PhotoPage = if (selection == null) PhotoPage.SOURCE else PhotoPage.PREVIEW,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val confirmationFailed: Boolean = false,
    val confirmed: Boolean = false,
    val galleryImportRejection: PhotoImportRejection? = null,
    val lens: CameraLens = CameraLens.BACK,
)

/**
 * Holds the picker dialog state, including the chosen camera lens.
 *
 * @param defaultLens Lens used until the user switches it, e.g. [CameraLens.FRONT] for selfies.
 * @property mirrorFrontCamera Whether front-camera photos are saved mirrored, like the preview.
 *   Only for selfies: mirrored text breaks receipt OCR and barcode scanning.
 */
class PhotoPickerViewModel(
    private val store: TemporaryPhotoStore,
    private val savedState: SavedStateHandle,
    private val defaultLens: CameraLens = CameraLens.BACK,
    val mirrorFrontCamera: Boolean = false,
) : ViewModel() {
  private fun restoreSelection(): SelectedPhoto? =
      savedState.get<String>(IMAGE_KEY)?.let { uri ->
        val source =
            if (savedState.get<Boolean>(CAMERA_KEY) == true) PhotoSource.CAMERA
            else PhotoSource.GALLERY
        SelectedPhoto(uri, source)
      }

  private val mutableState =
      MutableStateFlow(
          PhotoPickerState(
              selection = restoreSelection(),
              confirmed = savedState[CONFIRMED_KEY] ?: false,
              visible = savedState[VISIBLE_KEY] ?: false,
              lens = savedState.get<String>(LENS_KEY)?.let(CameraLens::valueOf) ?: defaultLens))
  val state = mutableState.asStateFlow()
  private var operation: Job? = null

  init {
    state.value.selection?.uri?.let { uri -> runOperation { store.validate(uri) } }
  }

  fun open() {
    if (state.value.busy) return
    savedState[VISIBLE_KEY] = true
    mutableState.update {
      it.copy(
          visible = true,
          page = if (it.selection == null) PhotoPage.SOURCE else PhotoPage.PREVIEW,
          failed = false,
          confirmationFailed = false)
    }
  }

  fun showCamera() {
    if (!state.value.busy)
        mutableState.update {
          it.copy(page = PhotoPage.CAMERA, failed = false, confirmationFailed = false)
        }
  }

  /** Switches between the back and front camera; the choice survives recreation. */
  fun switchLens() {
    if (state.value.busy) return
    val lens = state.value.lens.other()
    savedState[LENS_KEY] = lens.name
    mutableState.update { it.copy(lens = lens) }
  }

  fun showSources() {
    if (!state.value.busy)
        mutableState.update {
          it.copy(page = PhotoPage.SOURCE, failed = false, confirmationFailed = false)
        }
  }

  fun closePreview() {
    if (!state.value.busy) {
      savedState[VISIBLE_KEY] = false
      mutableState.update { it.copy(visible = false, page = PhotoPage.SOURCE) }
    }
  }

  fun reportFailure() {
    // A failed replacement must not make the previous image eligible for a second handoff.
    mutableState.update {
      it.copy(failed = true, confirmationFailed = false, galleryImportRejection = null)
    }
  }

  fun select(uri: String?) {
    if (uri != null && state.value.visible)
        runOperation { replace(SelectedPhoto(store.importImage(uri), PhotoSource.GALLERY)) }
  }

  fun capture(takePicture: suspend (String) -> Unit) {
    if (!state.value.visible) return
    runOperation {
      val uri = store.createCapture()
      try {
        takePicture(uri)
        store.validate(uri)
        replace(SelectedPhoto(uri, PhotoSource.CAMERA))
      } catch (failure: Throwable) {
        withContext(NonCancellable) { store.delete(uri) }
        throw failure
      }
    }
  }

  /** The consumer may read the URI until this suspending callback completes. No decoding here. */
  fun confirm(consume: (suspend (SelectedPhoto) -> Unit)? = null) {
    if (!state.value.visible) return
    val selection = state.value.selection ?: return
    if (state.value.confirmed) {
      closePreview()
      return
    }
    runOperation {
      store.validate(selection.uri)
      try {
        consume?.invoke(selection)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        mutableState.update { it.copy(confirmationFailed = true) }
        throw failure
      }
      savedState[CONFIRMED_KEY] = true
      savedState[VISIBLE_KEY] = false
      mutableState.update { it.copy(confirmed = true, visible = false) }
    }
  }

  fun reset() {
    if (state.value.busy) return
    val uri = state.value.selection?.uri
    savedState[IMAGE_KEY] = null
    savedState[CAMERA_KEY] = null
    savedState[CONFIRMED_KEY] = false
    savedState[VISIBLE_KEY] = false
    savedState[LENS_KEY] = null
    mutableState.value = PhotoPickerState(lens = defaultLens)
    if (uri != null) CoroutineScope(Dispatchers.Main.immediate).launch { store.delete(uri) }
  }

  /** Called when the form leaves composition, but not during an Activity recreation. */
  fun discard() {
    val pending = operation
    pending?.cancel()
    savedState[VISIBLE_KEY] = false
    mutableState.update { it.copy(visible = false, busy = true) }
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      mutableState.update { it.copy(busy = false) }
      reset()
    }
  }

  private suspend fun replace(selection: SelectedPhoto) {
    val previous = state.value.selection?.uri
    savedState[IMAGE_KEY] = selection.uri
    savedState[CAMERA_KEY] = selection.source == PhotoSource.CAMERA
    savedState[CONFIRMED_KEY] = false
    mutableState.update {
      it.copy(selection = selection, page = PhotoPage.PREVIEW, confirmed = false)
    }
    if (previous != null && previous != selection.uri)
        withContext(NonCancellable) { store.delete(previous) }
  }

  private fun runOperation(action: suspend () -> Unit) {
    if (state.value.busy) return
    mutableState.update {
      it.copy(
          busy = true, failed = false, confirmationFailed = false, galleryImportRejection = null)
    }
    operation =
        viewModelScope.launch {
          try {
            action()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (failure: Exception) {
            mutableState.update {
              it.copy(
                  failed = true,
                  galleryImportRejection = (failure as? PhotoImportValidationException)?.rejection)
            }
          } finally {
            mutableState.update { it.copy(busy = false) }
          }
        }
  }

  override fun onCleared() {
    val pending = operation
    pending?.cancel()
    // Finish a cancelled capture/consumer before releasing its private file.
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      state.value.selection?.uri?.let { store.delete(it) }
    }
  }

  companion object {
    // Retain the saved-state keys used before the picker was shared across features.
    const val IMAGE_KEY = "barcodePhotoUri"
    const val CAMERA_KEY = "barcodePhotoFromCamera"
    const val CONFIRMED_KEY = "barcodePhotoConfirmed"
    const val VISIBLE_KEY = "barcodePhotoVisible"
    /** Saved-state key of the chosen [CameraLens]. */
    const val LENS_KEY = "photoPickerCameraLens"
  }
}
