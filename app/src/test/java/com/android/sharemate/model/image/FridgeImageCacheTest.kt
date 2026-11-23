// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FridgeImageCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  private val first = reference("01")
  private val replacement = reference("02")
  private val third = reference("03")
  private val remote = RemoteFridgeImageReference("https://images.example.org/milk.webp?size=120")
  private val webp = "RIFFtestWEBP".toByteArray()
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
  private var currentUser = "alice"
  private var online = true

  @Test
  fun readsTheViewedPhotoAfterAnOfflineRestart() = runBlocking {
    val session = cache().session("alice", "home")
    assertFalse(session.load(first) { jpeg }.fromDisk)
    assertTrue(session.load(first, ::unexpectedDownload).fromDisk)

    online = false
    val restartedSession = cache().session("alice", "home")
    val photo = restartedSession.load(first, ::unexpectedDownload)
    assertTrue(photo.fromDisk)
    assertArrayEquals(jpeg, photo.bytes)
    assertCacheMiss(restartedSession, replacement)

    online = true
    val updated = jpeg.copyOf().apply { this[lastIndex] = 1 }
    assertNotEquals(session.memoryKey(first), session.memoryKey(replacement))
    assertFalse(restartedSession.load(replacement) { updated }.fromDisk)
    assertArrayEquals(updated, restartedSession.load(replacement, ::unexpectedDownload).bytes)
  }

  @Test
  fun remoteAndFirebaseImagesSurviveOfflineRestartAndUseTheSamePurge() = runBlocking {
    val session = cache().session("alice", "home")
    session.load(first) { jpeg }
    assertFalse(session.load(remote) { webp }.fromDisk)
    assertNotEquals(session.memoryKey(first), session.memoryKey(remote))

    online = false
    val restartedSession = cache().session("alice", "home")
    val image = restartedSession.load(remote, ::unexpectedDownload)
    assertTrue(image.fromDisk)
    assertArrayEquals(webp, image.bytes)
    assertTrue(restartedSession.load(first, ::unexpectedDownload).fromDisk)
    assertCacheMiss(restartedSession, remote.copy(url = remote.url.replace("120", "240")))
    assertCacheMiss(cache().session("alice", "other-home"), remote)

    restartedSession.revoke()
    val afterPurge = cache().session("alice", "home")
    assertCacheMiss(afterPurge, first)
    assertCacheMiss(afterPurge, remote)
  }

  @Test
  fun aRemoteImageCanEvictAPersonalPhotoFromTheSharedBudget() = runBlocking {
    val session = cache(maxBytes = webp.size.toLong()).session("alice", "home")
    session.load(first) { jpeg }
    session.load(remote) { webp }

    online = false
    assertCacheMiss(session, first)
    assertTrue(session.load(remote, ::unexpectedDownload).fromDisk)
  }

  @Test
  fun isolatesAccountsHouseholdsAndStorageBuckets() = runBlocking {
    val cache = cache()
    val alice = cache.session("alice", "home")
    alice.load(first) { jpeg }

    online = false
    val otherHousehold = cache.session("alice", "other-home")
    assertNotEquals(alice.memoryKey(first), otherHousehold.memoryKey(first))
    assertCacheMiss(otherHousehold, first)
    assertCacheMiss(cache(bucket = "other-bucket").session("alice", "home"), first)

    currentUser = "bob"
    val bob = cache.session("bob", "home")
    assertNotEquals(alice.memoryKey(first), bob.memoryKey(first))
    assertCacheMiss(bob, first)
    assertCacheMiss(alice, first)
  }

  @Test
  fun purgesOnlyTheRequestedHouseholdThenTheRestOfTheAccount() = runBlocking {
    val evicted = mutableListOf<String>()
    val cache = cache(evictMemory = { evicted += it })
    val alice = cache.session("alice", "home")
    val otherHome = cache.session("alice", "other-home")
    alice.load(first) { jpeg }
    otherHome.load(first) { jpeg }
    currentUser = "bob"
    val bob = cache.session("bob", "home")
    bob.load(first) { jpeg }
    currentUser = "alice"
    online = false

    cache.purgeHousehold("alice", "home")
    assertFalse(alice.isActive.value)
    assertTrue(alice.memoryKey(first).startsWith(evicted.single()))
    assertCacheMiss(cache.session("alice", "home"), first)
    assertTrue(otherHome.load(first, ::unexpectedDownload).fromDisk)

    cache.purgeAccount("alice")
    assertFalse(otherHome.isActive.value)
    assertTrue(evicted.any { otherHome.memoryKey(first).startsWith(it) })
    assertCacheMiss(cache().session("alice", "other-home"), first)
    currentUser = "bob"
    assertTrue(bob.isActive.value)
    assertTrue(bob.load(first, ::unexpectedDownload).fromDisk)
  }

  @Test
  fun aRevokedDownloadCannotRepopulateDisk() = runBlocking {
    val cache = cache()
    val session = cache.session("alice", "home")
    val downloadStarted = CompletableDeferred<Unit>()
    val resumeDownload = CompletableDeferred<Unit>()
    val pending = async {
      runCatching {
        session.load(first) {
          downloadStarted.complete(Unit)
          resumeDownload.await()
          jpeg
        }
      }
    }
    downloadStarted.await()
    session.revoke()
    resumeDownload.complete(Unit)

    assertTrue(pending.await().exceptionOrNull() is IOException)
    assertFalse(session.isActive.value)
    online = false
    assertCacheMiss(cache.session("alice", "home"), first)
  }

  @Test
  fun overlappingLoadsOfTheSameReferenceDownloadOnce() = runBlocking {
    val session = cache().session("alice", "home")
    val downloadStarted = CompletableDeferred<Unit>()
    val resumeDownload = CompletableDeferred<Unit>()
    val pending = async {
      session.load(first) {
        downloadStarted.complete(Unit)
        resumeDownload.await()
        jpeg
      }
    }
    downloadStarted.await()
    val second = async { session.load(first, ::unexpectedDownload) }
    resumeDownload.complete(Unit)

    assertFalse(pending.await().fromDisk)
    assertTrue(second.await().fromDisk)
  }

  @Test
  fun staysWithinOneBudgetAcrossHouseholdsAndEvictsTheOldestPhoto() = runBlocking {
    val cache = cache(maxBytes = 2L * jpeg.size)
    val firstHome = cache.session("alice", "home")
    val secondHome = cache.session("alice", "other-home")
    firstHome.load(first) { jpeg }
    val oldest = temporary.root.walkTopDown().single { it.extension == "photo" }
    assertTrue(oldest.setLastModified(1000L))
    secondHome.load(replacement) { jpeg }
    secondHome.load(third) { jpeg }

    online = false
    assertCacheMiss(firstHome, first)
    assertTrue(secondHome.load(replacement, ::unexpectedDownload).fromDisk)
    assertTrue(secondHome.load(third, ::unexpectedDownload).fromDisk)
    assertEquals(
        2L * jpeg.size, temporary.root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
  }

  @Test
  fun aCorruptEntryFallsBackToDownload() = runBlocking {
    val session = cache().session("alice", "home")
    session.load(first) { jpeg }
    temporary.root.walkTopDown().single { it.extension == "photo" }.writeText("corrupt")

    assertFalse(session.load(first) { jpeg }.fromDisk)
    assertTrue(session.load(first, ::unexpectedDownload).fromDisk)
  }

  @Test
  fun anUnavailableDiskDoesNotPreventDisplayingAFetchedPhoto() = runBlocking {
    val session =
        FridgeImageCache(temporary.newFile(), "bucket", { true }, { true }).session("alice", "home")

    assertArrayEquals(jpeg, session.load(first) { jpeg }.bytes)
  }

  private fun cache(
      bucket: String = "bucket",
      maxBytes: Long = 50L * 1024 * 1024,
      evictMemory: (String) -> Unit = {},
  ) =
      FridgeImageCache(
          temporary.root, bucket, { it == currentUser }, { online }, evictMemory, maxBytes)

  private suspend fun assertCacheMiss(
      session: FridgeImageCacheSession,
      reference: FridgeImageReference,
  ) {
    val error = runCatching { session.load(reference, ::unexpectedDownload) }.exceptionOrNull()
    assertTrue("Expected a cache miss, got $error", error is IOException)
  }

  private fun unexpectedDownload(): ByteArray = error("Unexpected download")

  private fun reference(suffix: String) =
      PersonalPhotoReference("itemPhotos/milk/00000000-0000-0000-0000-0000000000$suffix")
}
