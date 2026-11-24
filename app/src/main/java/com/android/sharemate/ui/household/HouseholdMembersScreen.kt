// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppBackHeader
import com.android.sharemate.ui.components.AppCard
import com.android.sharemate.ui.components.AppListRow
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.components.AppTag
import com.android.sharemate.ui.profile.ProfileAvatar
import com.android.sharemate.ui.theme.Dimens
import com.android.sharemate.ui.theme.StatusColor

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
 * Each member is a row of a card with their photo and name, and a "You" tag for the signed-in user.
 * Like the Settings header, a member without a photo gets a placeholder avatar and a member without
 * a name shows their email. The email is not shown next to a name, so members only see each other's
 * email when there is nothing else to recognize them by.
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
  Column(
      Modifier.fillMaxSize()
          .padding(
              horizontal = Dimens.screenHorizontalPadding, vertical = Dimens.screenVerticalPadding)
          .testTag(HouseholdMembersTestTags.SCREEN),
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingL),
  ) {
    AppBackHeader(
        title = membersTitle(state),
        onBack = onBack,
        backTestTag = HouseholdMembersTestTags.BACK,
    )
    when {
      state.hasError -> MembersError(onRetry)
      state.isLoading ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(HouseholdMembersTestTags.LOADING))
          }
      else -> MembersList(state.members)
    }
  }
}

/** "Members (4)" once the members are known, plain "Members" while loading or after an error. */
@Composable
private fun membersTitle(state: HouseholdMembersUiState): String =
    if (state.isLoading || state.hasError) stringResource(R.string.household_members_title)
    else stringResource(R.string.household_members_title_with_count, state.members.size)

/** The members in one card, separated by dividers. */
@Composable
private fun MembersList(members: List<HouseholdMember>) {
  Column(Modifier.verticalScroll(rememberScrollState()).testTag(HouseholdMembersTestTags.LIST)) {
    AppCard {
      members.forEachIndexed { index, member ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MemberRow(member)
      }
    }
  }
}

/** One member: avatar, name (or email), and "You" for the signed-in user. */
@Composable
private fun MemberRow(member: HouseholdMember) {
  val name = member.displayName ?: member.email ?: stringResource(R.string.household_member_unknown)
  val colors = MaterialTheme.colorScheme
  AppListRow(
      title = name,
      // Read as one item by screen readers, e.g. "Inge, You".
      modifier =
          Modifier.semantics(mergeDescendants = true) {}
              .testTag(HouseholdMembersTestTags.member(member.uid)),
      titleModifier = Modifier.testTag(HouseholdMembersTestTags.name(member.uid)),
      leading = {
        ProfileAvatar(
            photoUrl = member.photoUrl,
            size = HouseholdLayout.memberAvatarSize,
            iconSize = HouseholdLayout.memberAvatarIconSize,
            contentDescription = stringResource(R.string.household_member_photo, name),
            photoTestTag = HouseholdMembersTestTags.photo(member.uid),
            placeholderTestTag = HouseholdMembersTestTags.placeholder(member.uid),
        )
      },
      trailing =
          if (member.isCurrentUser) {
            {
              AppTag(
                  text = stringResource(R.string.household_members_you),
                  colors = StatusColor(colors.primaryContainer, colors.onPrimaryContainer),
                  modifier = Modifier.testTag(HouseholdMembersTestTags.you(member.uid)),
              )
            }
          } else null,
  )
}

@Composable
private fun MembersError(onRetry: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize(),
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
    AppPrimaryButton(
        text = stringResource(R.string.household_retry),
        onClick = onRetry,
        modifier = Modifier.testTag(HouseholdMembersTestTags.RETRY),
    )
  }
}
