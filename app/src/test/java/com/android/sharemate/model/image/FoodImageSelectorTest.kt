// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.image

import com.android.sharemate.model.image.FoodImageSearchResult.Failure
import com.android.sharemate.model.image.FoodImageSearchResult.Found
import com.android.sharemate.model.image.FoodImageSearchResult.NotFound
import com.android.sharemate.model.image.FoodImageSearchResult.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FoodImageSelectorTest {
  @Test
  fun personalPhotoIsKeptExactlyAndDoesNotCallRepository() = runBlocking {
    val repository = FakeRepository()
    val reference = " content://photos/food?id=42 "

    val selection = FoodImageSelector(repository).select("pâtes", reference)

    assertEquals(FoodImageSelection.PersonalPhoto(reference), selection)
    assertEquals(0, repository.calls)
  }

  @Test
  fun titleIsPassedAndFoundImageKeepsAllAttribution() = runBlocking {
    val repository = FakeRepository(result = Found(image()))

    val selection = FoodImageSelector(repository).select("  soupe  ")

    assertEquals("  soupe  ", repository.lastTitle)
    assertEquals(FoodImageSelection.RemoteImage(image()), selection)
  }

  @Test
  fun noResultSelectsGenericVisual() = runBlocking {
    assertEquals(
        FoodImageSelection.Generic, FoodImageSelector(FakeRepository(NotFound)).select("riz"))
  }

  @Test
  fun expectedRepositoryFailureSelectsGenericVisual() = runBlocking {
    assertEquals(
        FoodImageSelection.Generic,
        FoodImageSelector(FakeRepository(Failure(Reason.NETWORK))).select("riz"))
  }

  @Test
  fun blankPersonalPhotoIsAbsentAndSearches() = runBlocking {
    val repository = FakeRepository(result = Found(image()))

    val selection = FoodImageSelector(repository).select("riz", " \n\t ")

    assertEquals(FoodImageSelection.RemoteImage(image()), selection)
    assertEquals(1, repository.calls)
  }

  @Test
  fun cancellationIsPropagated() = runBlocking {
    val cancellation = CancellationException("cancelled")
    val repository =
        FakeRepository(
            deferred =
                CompletableDeferred<FoodImageSearchResult>().also {
                  it.completeExceptionally(cancellation)
                })

    try {
      FoodImageSelector(repository).select("riz")
      throw AssertionError("Cancellation must propagate")
    } catch (exception: CancellationException) {
      assertEquals(cancellation.message, exception.message)
    }
  }

  private fun image() =
      FoodImage(
          url = "https://images.example.org/food.jpg",
          author = "Jane Doe",
          sourceName = "Openverse",
          sourceUrl = "https://example.org/photos/42",
          title = "Soupe",
          license = "cc0",
          licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
          licenseVersion = "1.0",
          authorUrl = "https://example.org/jane")

  private class FakeRepository(
      private val result: FoodImageSearchResult = NotFound,
      private val deferred: CompletableDeferred<FoodImageSearchResult>? = null
  ) : FoodImageRepository {
    var calls = 0
    var lastTitle: String? = null

    override suspend fun searchByTitle(title: String): FoodImageSearchResult {
      calls++
      lastTitle = title
      return deferred?.await() ?: result
    }
  }
}
