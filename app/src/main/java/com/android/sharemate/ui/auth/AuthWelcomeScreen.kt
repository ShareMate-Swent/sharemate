package com.android.sharemate.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

@Composable
fun AuthWelcomeScreen(
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
  AuthTheme {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      BoxWithConstraints(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier.widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .testTag("auth_welcome"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(56.dp))
                Image(
                    painter = painterResource(R.drawable.auth_fridge),
                    contentDescription = null,
                    modifier =
                        Modifier.widthIn(max = 312.dp)
                            .fillMaxWidth()
                            .aspectRatio(624f / 560f)
                            .clip(RoundedCornerShape(28.dp)))
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.auth_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.auth_welcome_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
              }
              Column(
                  modifier = Modifier.padding(top = 32.dp),
                  horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onLogIn,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("auth_welcome_login")) {
                          Text(stringResource(R.string.auth_log_in))
                        }
                    TextButton(
                        onClick = onCreateAccount,
                        modifier = Modifier.testTag("auth_welcome_signup")) {
                          Text(stringResource(R.string.auth_create_account))
                        }
                    Text(
                        text = stringResource(R.string.auth_sign_in_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                  }
            }
      }
    }
  }
}
