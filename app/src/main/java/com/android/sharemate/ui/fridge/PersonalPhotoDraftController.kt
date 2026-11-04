// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.image.PersonalPhotoDraftError
import com.android.sharemate.model.image.PersonalPhotoDraftException
import com.android.sharemate.model.image.PersonalPhotoDraftStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PersonalPhotoDraftState(
    val draft: File? = null,
    val importing: Boolean = false,
    val selectionError: PersonalPhotoDraftError? = null,
)

/** Owns only the form's private selection; an upload takes ownership after item creation. */
class PersonalPhotoDraftController(
    private val drafts: PersonalPhotoDraftStore,
    private val scope: CoroutineScope,
) {
  private val mutableState = MutableStateFlow(PersonalPhotoDraftState())
  val state = mutableState.asStateFlow()
  private var selection: Job? = null
  var selectionGeneration = 0L
    private set

  /** Picker cancellation and an invalid replacement preserve the previous valid selection. */
  fun select(uri: String?) {
    if (uri == null) return
    val token = ++selectionGeneration
    selection?.cancel()
    mutableState.update { it.copy(importing = true, selectionError = null) }
    selection =
        scope.launch {
          var imported: File? = null
          try {
            val file = drafts.importPhoto(uri)
            imported = file
            currentCoroutineContext().ensureActive()
            if (token != selectionGeneration) return@launch
            val previous = state.value.draft
            mutableState.value = PersonalPhotoDraftState(draft = file)
            imported = null
            previous?.let { drafts.delete(it) }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (failure: Exception) {
            if (token == selectionGeneration)
                mutableState.update {
                  it.copy(
                      selectionError =
                          (failure as? PersonalPhotoDraftException)?.reason
                              ?: PersonalPhotoDraftError.UNREADABLE)
                }
          } finally {
            withContext(NonCancellable) { imported?.let { drafts.delete(it) } }
            if (token == selectionGeneration) mutableState.update { it.copy(importing = false) }
          }
        }
  }

  /** Detaches the captured selection without deleting the file now owned by an upload. */
  fun take(file: File) {
    if (state.value.draft == file) {
      selectionGeneration++
      mutableState.value = PersonalPhotoDraftState()
    }
  }

  fun discardDraft() {
    val file = state.value.draft
    selectionGeneration++
    selection?.cancel()
    mutableState.value = PersonalPhotoDraftState()
    if (file != null) scope.launch { withContext(NonCancellable) { drafts.delete(file) } }
  }

  /** Wait for import cancellation before local cleanup; no detached network work. */
  fun close() {
    val file = state.value.draft
    val pending = selection
    selectionGeneration++
    pending?.cancel()
    mutableState.value = PersonalPhotoDraftState()
    CoroutineScope(Dispatchers.IO).launch {
      pending?.join()
      file?.let { drafts.delete(it) }
    }
  }
}
