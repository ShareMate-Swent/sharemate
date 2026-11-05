// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoReference
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.model.item.ItemImageUpdateResult
import com.android.sharemate.model.item.ItemRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class PersonalPhotoDraft(
    val file: File? = null,
    val importing: Boolean = false,
    val rejected: Boolean = false
)

/** Owns one form draft and the photo work of this fridge session. */
class PersonalPhotoController(
    private val items: ItemRepository,
    private val storage: PersonalPhotoRepository,
    private val files: PersonalPhotoFiles,
    private val scope: CoroutineScope,
) {
  private val mutableDraft = MutableStateFlow(PersonalPhotoDraft())
  val draft = mutableDraft.asStateFlow()
  private val mutableFailure = MutableStateFlow<String?>(null)
  val failure = mutableFailure.asStateFlow()
  private var importing: Job? = null
  private var errorTimer: Job? = null
  private val uploads = mutableMapOf<String, Job>()

  fun select(uri: String?) {
    if (uri == null || draft.value.importing) return
    val previous = draft.value.file
    mutableDraft.value = PersonalPhotoDraft(previous, importing = true)
    importing =
        scope.launch {
          var candidate: File? = null
          try {
            candidate = files.copy(uri)
            ensureActive()
            mutableDraft.value = PersonalPhotoDraft(candidate)
            candidate = null
            previous?.let { release(it) }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            ensureActive()
            mutableDraft.value = PersonalPhotoDraft(previous, rejected = true)
          } finally {
            candidate?.let { release(it) }
          }
        }
  }

  /** Discards form ownership. It can also run after the session scope has been cancelled. */
  fun discardDraft() {
    importing?.cancel()
    val file = takeDraft() ?: return
    scope.launch(start = CoroutineStart.UNDISPATCHED) { release(file) }
  }

  /** Transfers the private copy to the upload after item creation is confirmed. */
  fun takeDraft(): File? {
    val file = draft.value.file
    mutableDraft.value = PersonalPhotoDraft()
    return file
  }

  fun attachCreated(itemId: String, name: String, file: File) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      uploads[itemId] = coroutineContext.job
      try {
        ensureActive()
        val attached =
            withTimeoutOrNull(PHOTO_OPERATION_TIMEOUT_MS) {
              val reference = storage.upload(itemId, file).getOrThrow()
              ensureActive()
              when (val result =
                  items.updateItemImageIfAbsent(
                      itemId, name, ItemImage(reference = reference.path))) {
                ItemImageUpdateResult.Updated -> true
                ItemImageUpdateResult.Ignored -> {
                  storage.delete(reference)
                  false
                }
                // A failed wait does not prove the transaction never committed.
                is ItemImageUpdateResult.Error -> throw result.cause
              }
            }
        if (attached != true) reportFailure(name)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        reportFailure(name)
      } finally {
        release(file)
        uploads.remove(itemId)
      }
    }
  }

  suspend fun load(path: String): ByteArray =
      storage.load(PersonalPhotoReference(path)).getOrThrow()

  suspend fun prepareRemoval(item: Item) {
    val upload = uploads[item.id]
    val hasPhoto =
        item.image?.let {
          it.origin == ItemImageOrigin.PERSONAL_PHOTO && it.reference.startsWith("itemPhotos/")
        } == true
    if (upload == null && !hasPhoto) return
    // Keep the item present until Storage authorizes and confirms removal.
    upload?.join()
    check(
        withTimeoutOrNull(PHOTO_OPERATION_TIMEOUT_MS) {
          storage.deleteAttached(item.id).getOrThrow()
          true
        } == true)
  }

  private suspend fun release(file: File) = withContext(NonCancellable) { files.delete(file) }

  private fun reportFailure(name: String) {
    errorTimer?.cancel()
    mutableFailure.value = name
    errorTimer =
        scope.launch {
          delay(PHOTO_ERROR_DURATION_MS)
          mutableFailure.value = null
        }
  }

  private companion object {
    const val PHOTO_OPERATION_TIMEOUT_MS = 20_000L
    const val PHOTO_ERROR_DURATION_MS = 10_000L
  }
}
