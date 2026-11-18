package com.android.sharemate.model.receipt

import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReceiptPhotoStoreTest {
  @Test
  fun emptyAndOversizedPhotosAreRejectedWithoutReplacingRetainedPhoto() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidReceiptPhotoStore(app, StandardTestDispatcher(testScheduler))
    val id = UUID.randomUUID().toString()
    val valid = File.createTempFile("valid-scan-", ".jpg", app.cacheDir)
    val empty = File.createTempFile("empty-scan-", ".jpg", app.cacheDir)
    val oversized = File.createTempFile("oversized-scan-", ".jpg", app.cacheDir)
    try {
      valid.writeBytes(byteArrayOf(1, 2, 3))
      val retained =
          File(checkNotNull(Uri.parse(store.retain(Uri.fromFile(valid).toString(), id)).path))
      oversized.outputStream().use { it.write(ByteArray(20 * 1024 * 1024 + 1)) }

      assertTrue(runCatching { store.retain(Uri.fromFile(empty).toString(), id) }.isFailure)
      val tooLarge = runCatching { store.retain(Uri.fromFile(oversized).toString(), id) }
      assertTrue(tooLarge.exceptionOrNull() is IllegalArgumentException)
      assertArrayEquals(byteArrayOf(1, 2, 3), retained.readBytes())
      assertTrue(retained.exists())
      assertEquals(1, File(app.filesDir, "receipts").listFiles()?.size)
    } finally {
      valid.delete()
      empty.delete()
      oversized.delete()
      store.delete(id)
    }
  }

  @Test
  fun retainedPhotoSurvivesSourceDeletionAndCanBeRemoved() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val source = File.createTempFile("scan-", ".jpg", app.cacheDir)
    val bytes = byteArrayOf(1, 2, 3, 4, 5)
    source.writeBytes(bytes)
    val id = UUID.randomUUID().toString()
    val store = AndroidReceiptPhotoStore(app, StandardTestDispatcher(testScheduler))

    val retained =
        File(checkNotNull(Uri.parse(store.retain(Uri.fromFile(source).toString(), id)).path))
    source.delete()
    assertArrayEquals(bytes, retained.readBytes())
    assertTrue(retained.path.startsWith(app.filesDir.path))

    store.delete(id)
    assertFalse(retained.exists())
  }

  @Test
  fun missingSourceAndInvalidIdentityDoNotLeaveAReceiptPhoto() = runTest {
    val app = RuntimeEnvironment.getApplication()
    val store = AndroidReceiptPhotoStore(app, StandardTestDispatcher(testScheduler))
    val id = UUID.randomUUID().toString()
    val missing = File(app.cacheDir, "missing-$id.jpg")

    assertTrue(runCatching { store.retain(Uri.fromFile(missing).toString(), id) }.isFailure)
    assertFalse(File(app.filesDir, "receipts/$id.jpg").exists())
    assertTrue(runCatching { store.retain(Uri.fromFile(missing).toString(), "../other") }.isFailure)
    assertTrue(runCatching { store.delete("../other") }.isFailure)
    val nonCanonical = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
    assertTrue(
        runCatching { store.retain(Uri.fromFile(missing).toString(), nonCanonical) }.isFailure)
    assertTrue(runCatching { store.delete(nonCanonical) }.isFailure)
  }
}
