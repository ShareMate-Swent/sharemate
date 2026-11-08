// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import com.android.sharemate.model.profile.InMemoryUserProfileRepository
import com.android.sharemate.model.profile.UserProfile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdMembersViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val profiles = InMemoryUserProfileRepository()

  private val alex =
      UserProfile(
          uid = "alex",
          displayName = "Alex Morgan",
          photoUrl = "https://example.org/alex.png",
          email = "alex@example.com")
  private val sam = UserProfile(uid = "sam", email = "sam@example.com")
  private val me = UserProfile(uid = "me", displayName = "Inge", email = "inge@example.com")

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    profiles.profiles.value = listOf(alex, sam, me).associateBy { it.uid }
    profiles.memberIds.value = mapOf("home" to listOf("alex", "me", "sam"))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel() = HouseholdMembersViewModel(profiles, "home", currentUserId = "me")

  private fun member(profile: UserProfile, isCurrentUser: Boolean = false) =
      HouseholdMember(
          uid = profile.uid,
          displayName = profile.displayName,
          email = profile.email,
          photoUrl = profile.photoUrl,
          isCurrentUser = isCurrentUser)

  @Test
  fun loadsTheMembersWithTheCurrentUserFirstAndTheOthersInHouseholdOrder() = runTest {
    val viewModel = createViewModel()
    assertEquals(HouseholdMembersUiState(isLoading = true), viewModel.state.value)

    advanceUntilIdle()

    assertEquals(
        HouseholdMembersUiState(
            members = listOf(member(me, isCurrentUser = true), member(alex), member(sam)),
            isLoading = false),
        viewModel.state.value)
  }

  @Test
  fun joinsAndLeavesUpdateTheListWhileItIsOpen() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    profiles.memberIds.value = mapOf("home" to listOf("me", "sam", "newcomer"))
    advanceUntilIdle()

    // The newcomer has not saved a profile yet, so only their uid is known.
    assertEquals(
        listOf(member(me, isCurrentUser = true), member(sam), HouseholdMember(uid = "newcomer")),
        viewModel.state.value.members)

    profiles.memberIds.value = mapOf("home" to listOf("me"))
    advanceUntilIdle()

    assertEquals(listOf(member(me, isCurrentUser = true)), viewModel.state.value.members)
  }

  @Test
  fun aFailedLoadShowsAnErrorAndRetryLoadsTheList() = runTest {
    profiles.observeFailure = IOException("offline")
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals(HouseholdMembersUiState(isLoading = false, hasError = true), viewModel.state.value)

    profiles.observeFailure = null
    viewModel.retry()
    assertEquals(HouseholdMembersUiState(isLoading = true), viewModel.state.value)
    advanceUntilIdle()

    assertEquals(
        listOf(member(me, isCurrentUser = true), member(alex), member(sam)),
        viewModel.state.value.members)
    assertEquals(false, viewModel.state.value.hasError)
  }

  @Test
  fun retryWithoutAnErrorKeepsTheCurrentList() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    val loaded = viewModel.state.value

    // A retry that restarted the flow would fail now and show an error.
    profiles.observeFailure = IOException("offline")
    viewModel.retry()
    advanceUntilIdle()

    assertEquals(loaded, viewModel.state.value)
  }

  @Test
  fun aBlankHouseholdIdShowsAnErrorInsteadOfCrashing() = runTest {
    val viewModel = HouseholdMembersViewModel(profiles, " ", currentUserId = "me")
    advanceUntilIdle()

    assertEquals(HouseholdMembersUiState(isLoading = false, hasError = true), viewModel.state.value)
  }
}
