package com.android.sharemate.ui.barcode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.image.BarcodePhotoStore
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

data class BarcodePhotoState(
    val uri: String? = null,
    val visible: Boolean = false,
    val page: PhotoPage = PhotoPage.SOURCE,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val confirmed: Boolean = false,
)

class BarcodePhotoViewModel(
    private val store: BarcodePhotoStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
  private val mutableState =
      MutableStateFlow(
          BarcodePhotoState(
              uri = savedState[IMAGE_KEY],
              confirmed = savedState[CONFIRMED_KEY] ?: false,
              visible = savedState[VISIBLE_KEY] ?: false,
              page =
                  if (savedState.get<String>(IMAGE_KEY) != null) PhotoPage.PREVIEW
                  else PhotoPage.SOURCE))
  val state = mutableState.asStateFlow()
  private var operation: Job? = null

  init {
    state.value.uri?.let { uri -> runOperation { store.validate(uri) } }
  }

  fun open() {
    if (state.value.busy) return
    savedState[VISIBLE_KEY] = true
    mutableState.update {
      it.copy(
          visible = true,
          page = if (it.uri == null) PhotoPage.SOURCE else PhotoPage.PREVIEW,
          failed = false)
    }
  }

  fun showCamera() {
    if (!state.value.busy) mutableState.update { it.copy(page = PhotoPage.CAMERA, failed = false) }
  }

  fun showSources() {
    if (!state.value.busy) mutableState.update { it.copy(page = PhotoPage.SOURCE, failed = false) }
  }

  fun closePreview() {
    if (!state.value.busy) {
      savedState[VISIBLE_KEY] = false
      mutableState.update { it.copy(visible = false, page = PhotoPage.SOURCE) }
    }
  }

  fun reportFailure() {
    // A failed replacement must not make the previous image eligible for a second handoff.
    mutableState.update { it.copy(failed = true) }
  }

  fun select(uri: String?) {
    if (uri != null && state.value.visible) runOperation { replace(store.importImage(uri)) }
  }

  fun capture(takePicture: suspend (String) -> Unit) {
    if (!state.value.visible) return
    runOperation {
      val uri = store.createCapture()
      try {
        takePicture(uri)
        store.validate(uri)
        replace(uri)
      } catch (failure: Throwable) {
        withContext(NonCancellable) { store.delete(uri) }
        throw failure
      }
    }
  }

  /** The consumer may read the URI until this suspending callback completes. No decoding here. */
  fun confirm(consume: (suspend (String) -> Unit)? = null) {
    if (!state.value.visible) return
    val uri = state.value.uri ?: return
    if (state.value.confirmed) {
      closePreview()
      return
    }
    runOperation {
      store.validate(uri)
      consume?.invoke(uri)
      savedState[CONFIRMED_KEY] = true
      savedState[VISIBLE_KEY] = false
      mutableState.update { it.copy(confirmed = true, visible = false) }
    }
  }

  fun reset() {
    if (state.value.busy) return
    val uri = state.value.uri
    savedState[IMAGE_KEY] = null
    savedState[CONFIRMED_KEY] = false
    savedState[VISIBLE_KEY] = false
    mutableState.value = BarcodePhotoState()
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

  private suspend fun replace(uri: String) {
    val previous = state.value.uri
    savedState[IMAGE_KEY] = uri
    savedState[CONFIRMED_KEY] = false
    mutableState.update { it.copy(uri = uri, page = PhotoPage.PREVIEW, confirmed = false) }
    if (previous != null && previous != uri) withContext(NonCancellable) { store.delete(previous) }
  }

  private fun runOperation(action: suspend () -> Unit) {
    if (state.value.busy) return
    mutableState.update { it.copy(busy = true, failed = false) }
    operation =
        viewModelScope.launch {
          try {
            action()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            reportFailure()
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
      state.value.uri?.let { store.delete(it) }
    }
  }

  companion object {
    const val IMAGE_KEY = "barcodePhotoUri"
    const val CONFIRMED_KEY = "barcodePhotoConfirmed"
    const val VISIBLE_KEY = "barcodePhotoVisible"
  }
}
