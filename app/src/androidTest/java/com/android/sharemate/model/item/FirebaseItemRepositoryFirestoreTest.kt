// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.item

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseItemRepositoryFirestoreTest {
  private val firestore = FirebaseEmulator.firestore
  private lateinit var repository: FirebaseItemRepository

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
    repository = FirebaseItemRepository(firestore)
  }

  @After
  fun tearDown() {
    FirebaseEmulator.clear()
  }

  @Test
  fun addAndReadPreservesCategoryReferenceAndCredits() = runBlocking {
    val userId = FirebaseEmulator.signInAs("item-round-trip")
    val household = HouseholdRepositoryFirestore(firestore).createHousehold("Home", userId)
    val expectedImage =
        ItemImage(
            origin = ItemImageOrigin.REMOTE_IMAGE,
            reference = "https://images.example.org/milk.jpg",
            author = "Jane Doe",
            sourceName = "Photo library",
            sourceUrl = "https://example.org/photos/42",
            title = "Fresh milk",
            license = "by",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            licenseVersion = "4.0",
            authorUrl = "https://example.org/jane")

    val itemId =
        repository.addItem(
            Item(
                name = "Milk",
                ownerId = userId,
                householdId = household.id,
                category = "Dairy",
                image = expectedImage))

    val stored =
        repository
            .getSharedItems(household.id)
            .first { items -> items.any { it.id == itemId } }
            .single { it.id == itemId }

    assertEquals("Dairy", stored.category)
    assertNotNull(stored.image)
    assertEquals(expectedImage, stored.image)
  }
}
