// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.android.sharemate.R
import com.android.sharemate.ui.profile.ProfileAvatar
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [HouseholdMembersScreen]. */
object HouseholdMembersTestTags {
  const val SCREEN = "household_members_screen"
  const val BACK = "household_members_back"
  const val LOADING = "household_members_loading"
  const val ERROR = "household_members_error"
  const val RETRY = "household_members_retry"
  const val LIST = "household_members_list"

  fun member(uid: String) = "household_member_$uid"

  fun name(uid: String) = "household_member_name_$uid"

  fun you(uid: String) = "household_member_you_$uid"

  fun photo(uid: String) = "household_member_photo_$uid"

  fun placeholder(uid: String) = "household_member_placeholder_$uid"
}

/**
 * The members of the user's household, opened from the member count in Settings.
 *
 * Each member shows their photo and name. Like the Settings header, a member without a photo gets a
 * placeholder avatar and a member without a name shows their email. The email is not shown next to
 * a name, so members only see each other's email when there is nothing else to recognize them by.
 *
 * @param state Members, loading and error state from [HouseholdMembersViewModel].
 * @param onRetry Loads the list again after an error.
 * @param onBack Returns to Settings.
 */
@Composable
fun HouseholdMembersScreen(
    state: HouseholdMembersUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
  Column(Modifier.fillMaxSize().testTag(HouseholdMembersTestTags.SCREEN)) {
    HouseholdTopBar(
        title = stringResource(R.string.household_members_title),
        onBack = onBack,
        backTestTag = HouseholdMembersTestTags.BACK,
    )
    when {
      state.hasError -> MembersError(onRetry)
      state.isLoading ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(HouseholdMembersTestTags.LOADING))
          }
      else ->
          LazyColumn(
              modifier = Modifier.fillMaxSize().testTag(HouseholdMembersTestTags.LIST),
              contentPadding =
                  PaddingValues(
                      horizontal = Dimens.screenHorizontalPadding, vertical = Dimens.spacingXs),
          ) {
            items(state.members, key = HouseholdMember::uid) { member -> MemberRow(member) }
          }
    }
  }
}

/** One member: avatar, name (or email), and "You" for the signed-in user. */
@Composable
private fun MemberRow(member: HouseholdMember) {
  val name = member.displayName ?: member.email ?: stringResource(R.string.household_member_unknown)
  Row(
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingM),
      verticalAlignment = Alignment.CenterVertically,
      // Read as one item by screen readers, e.g. "Inge, You".
      modifier =
          Modifier.fillMaxWidth()
              .padding(vertical = Dimens.spacingS)
              .semantics(mergeDescendants = true) {}
              .testTag(HouseholdMembersTestTags.member(member.uid)),
  ) {
    ProfileAvatar(
        photoUrl = member.photoUrl,
        size = HouseholdLayout.memberAvatarSize,
        iconSize = HouseholdLayout.memberAvatarIconSize,
        contentDescription = stringResource(R.string.household_member_photo, name),
        photoTestTag = HouseholdMembersTestTags.photo(member.uid),
        placeholderTestTag = HouseholdMembersTestTags.placeholder(member.uid),
    )
    Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).testTag(HouseholdMembersTestTags.name(member.uid)),
    )
    if (member.isCurrentUser) {
      Text(
          text = stringResource(R.string.household_members_you),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag(HouseholdMembersTestTags.you(member.uid)),
      )
    }
  }
}

@Composable
private fun MembersError(onRetry: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.screenHorizontalPadding),
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingM, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = stringResource(R.string.household_members_error),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(HouseholdMembersTestTags.ERROR),
    )
    Button(
        onClick = onRetry,
        modifier = Modifier.height(Dimens.buttonHeight).testTag(HouseholdMembersTestTags.RETRY),
    ) {
      Text(stringResource(R.string.household_retry))
    }
  }
}
