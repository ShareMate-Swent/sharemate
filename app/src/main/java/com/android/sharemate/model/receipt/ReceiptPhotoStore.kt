package com.android.sharemate.model.receipt

import android.content.Context
import android.net.Uri
import com.android.sharemate.model.image.copyPhotoTo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies a scanned photo into private durable storage before its temporary source is released. */
interface ReceiptPhotoStore {
  suspend fun retain(sourceUri: String, receiptId: String): String

  suspend fun delete(receiptId: String)
}

class AndroidReceiptPhotoStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReceiptPhotoStore {
  private val resolver = context.applicationContext.contentResolver
  private val directory = File(context.applicationContext.filesDir, "receipts")

  override suspend fun retain(sourceUri: String, receiptId: String): String =
      withContext(dispatcher) {
        val id = UUID.fromString(receiptId).toString()
        require(id == receiptId)
        check(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, "$id.jpg")
        val pending = File.createTempFile("receipt-", ".tmp", directory)
        try {
          resolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
            pending.outputStream().use { output ->
              input.copyPhotoTo(output, MAX_PHOTO_BYTES, { ensureActive() }) {
                IllegalArgumentException("Receipt photo exceeds size limit")
              }
            }
          } ?: error("Receipt photo is unavailable")
          check(pending.length() in 1..MAX_PHOTO_BYTES)
          check(pending.renameTo(destination))
          Uri.fromFile(destination).toString()
        } finally {
          pending.delete()
        }
      }

  override suspend fun delete(receiptId: String) =
      withContext(dispatcher) {
        val id = UUID.fromString(receiptId).toString()
        require(id == receiptId)
        File(directory, "$id.jpg").delete()
        Unit
      }

  private companion object {
    const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
  }
}
