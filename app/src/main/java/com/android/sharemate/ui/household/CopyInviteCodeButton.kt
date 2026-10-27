// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.android.sharemate.R

/** Round button that copies [inviteCode] to the clipboard, used wherever the code is shown. */
@Composable
fun CopyInviteCodeButton(inviteCode: String, modifier: Modifier = Modifier) {
  val clipboard = LocalClipboardManager.current
  val colors = MaterialTheme.colorScheme
  IconButton(
      onClick = { clipboard.setText(AnnotatedString(inviteCode)) },
      modifier = modifier,
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
