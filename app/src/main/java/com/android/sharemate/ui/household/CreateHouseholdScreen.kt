// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

/** Test tags used by [CreateHouseholdScreen]. */
object CreateHouseholdTestTags {
  const val NAME = "create_household_name"
  const val CREATE = "create_household_button"
  const val BACK = "create_household_back"
  const val COPY_INVITE_CODE = "copy_invite_code"
  const val SHARE_INVITE_CODE = "share_invite_code"
  const val CONTINUE = "create_household_continue"
  const val LOADING = "create_household_loading"
  const val ERROR = "create_household_error"
}

/** Screen for creating a household and sharing its invite code. */
@Composable
fun CreateHouseholdScreen(
    viewModel: CreateHouseholdViewModel,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    if (state.inviteCode == null) {
      CreateHouseholdForm(state, viewModel, onBack)
    } else {
      HouseholdReady(state.inviteCode, onContinue)
    }
  }
}

/** Top bar shared by the household form screens: a back arrow and a centered title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseholdTopBar(title: String, onBack: () -> Unit, backTestTag: String) {
  CenterAlignedTopAppBar(
      title = { Text(title) },
      navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTestTag)) {
          Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.back),
          )
        }
      },
      colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
      windowInsets = WindowInsets(0),
  )
}

@Composable
private fun ColumnScope.CreateHouseholdForm(
    state: CreateHouseholdUiState,
    viewModel: CreateHouseholdViewModel,
    onBack: () -> Unit,
) {
  HouseholdTopBar(
      title = stringResource(R.string.create_household_title),
      onBack = onBack,
      backTestTag = CreateHouseholdTestTags.BACK,
  )
  Spacer(Modifier.height(16.dp))
  Text(
      text = stringResource(R.string.create_household_description),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(16.dp))
  OutlinedTextField(
      value = state.name,
      onValueChange = viewModel::updateName,
      label = { Text(stringResource(R.string.household_name)) },
      placeholder = { Text(stringResource(R.string.household_name_placeholder)) },
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.NAME),
      enabled = !state.isLoading,
      singleLine = true,
  )
  state.error?.let {
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.household_generic_error),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(CreateHouseholdTestTags.ERROR),
    )
  }
  Spacer(Modifier.weight(1f))
  Button(
      onClick = viewModel::createHousehold,
      enabled = state.name.isNotBlank() && !state.isLoading,
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CREATE),
  ) {
    if (state.isLoading) {
      CircularProgressIndicator(
          modifier = Modifier.size(20.dp).testTag(CreateHouseholdTestTags.LOADING),
          color = MaterialTheme.colorScheme.onPrimary,
          strokeWidth = 2.dp,
      )
    } else {
      Text(stringResource(R.string.create_household))
    }
  }
}

@Composable
private fun ColumnScope.HouseholdReady(inviteCode: String, onContinue: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val clipboard = LocalClipboardManager.current
  val context = LocalContext.current
  Spacer(Modifier.height(48.dp))
  Box(
      modifier =
          Modifier.align(Alignment.CenterHorizontally)
              .size(96.dp)
              .shadow(8.dp, CircleShape)
              .background(colors.primary, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = colors.onPrimary,
        modifier = Modifier.size(48.dp),
    )
  }
  Spacer(Modifier.height(24.dp))
  Text(
      text = stringResource(R.string.household_ready_title),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
  )
  Spacer(Modifier.height(12.dp))
  Text(
      text = stringResource(R.string.household_ready_description),
      style = MaterialTheme.typography.bodyLarge,
      color = colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
  )
  Spacer(Modifier.weight(1f))
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(28.dp),
      color = colors.surfaceContainerLowest,
      border = BorderStroke(1.dp, colors.primaryContainer),
      shadowElevation = 2.dp,
  ) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = stringResource(R.string.household_invite_code),
          style = MaterialTheme.typography.labelLarge,
          color = colors.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = inviteCode,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onPrimaryContainer,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(inviteCode)) },
            modifier = Modifier.testTag(CreateHouseholdTestTags.COPY_INVITE_CODE),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = colors.primaryContainer, contentColor = colors.primary),
        ) {
          Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = stringResource(R.string.copy_invite_code),
          )
        }
      }
      TextButton(
          onClick = {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, inviteCode)
            context.startActivity(Intent.createChooser(send, null))
          },
          modifier = Modifier.testTag(CreateHouseholdTestTags.SHARE_INVITE_CODE),
      ) {
        Text(stringResource(R.string.share_invite_code))
      }
    }
  }
  Spacer(Modifier.weight(1f))
  Button(
      onClick = onContinue,
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CONTINUE),
  ) {
    Text(stringResource(R.string.go_to_my_fridge))
  }
}
