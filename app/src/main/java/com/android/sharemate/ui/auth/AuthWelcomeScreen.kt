// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.toSize
import com.android.sharemate.R
import com.android.sharemate.ui.startup.LocalLaunchState
import com.android.sharemate.ui.startup.LocalLaunchVisible
import com.android.sharemate.ui.startup.ShareMateBrand
import com.android.sharemate.ui.theme.Dimens
import com.android.sharemate.ui.theme.ShareMateBrandColors

/**
 * First screen of the entry flow: the handwritten brand, the app's pitch, and the ways to get in.
 * Google is the main action; email log in and sign up open the form.
 *
 * @param onLogIn Opens the email log in form.
 * @param onCreateAccount Opens the email sign up form.
 * @param onGoogleSignIn Starts Google sign-in.
 * @param isLoading Whether Google sign-in is running; every action is disabled meanwhile.
 * @param error The last sign-in error, if any.
 */
@Composable
fun AuthWelcomeScreen(
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    modifier: Modifier = Modifier,
    onGoogleSignIn: () -> Unit = {},
    isLoading: Boolean = false,
    error: AuthUiError? = null,
) {
  MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      BoxWithConstraints(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier.widthIn(max = AuthLayout.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = this@BoxWithConstraints.maxHeight)
                    .padding(vertical = Dimens.spacingL)
                    .testTag(AuthTestTags.WELCOME),
            verticalArrangement = Arrangement.Center) {
              BrandHeader()
              Column(
                  modifier =
                      Modifier.padding(horizontal = Dimens.spacingL)
                          .padding(top = Dimens.spacingXs),
                  verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
                    Text(
                        text = stringResource(R.string.auth_welcome_title),
                        style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = stringResource(R.string.auth_welcome_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Dimens.spacingXs))
                    WelcomeActions(onLogIn, onCreateAccount, onGoogleSignIn, isLoading, error)
                  }
            }
      }
    }
  }
}

/** Keeps the ShareMate identity visible while the user chooses a sign-in method. */
@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
  val launchState = LocalLaunchState.current
  val launchVisible = LocalLaunchVisible.current
  DisposableEffect(launchState) { onDispose { launchState?.welcomeBrandBounds = null } }
  ShareMateBrand(
      modifier =
          modifier
              .testTag(AuthTestTags.BRAND_HEADER)
              .onGloballyPositioned {
                val bounds = Rect(it.positionInRoot(), it.size.toSize())
                launchState?.welcomeBrandBounds = bounds
              }
              .graphicsLayer { alpha = if (launchVisible) 0f else 1f })
}

@Composable
private fun WelcomeActions(
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onGoogleSignIn: () -> Unit,
    isLoading: Boolean,
    error: AuthUiError?,
) {
  Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
        error?.let {
          Text(
              stringResource(it.messageResource()),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.testTag(AuthTestTags.ERROR))
        }
        if (isLoading) {
          CircularProgressIndicator(Modifier.testTag(AuthTestTags.GOOGLE_LOADING))
        }
        AuthButton(
            onClick = onGoogleSignIn,
            enabled = !isLoading,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = ShareMateBrandColors.mintName, contentColor = Color.White),
            modifier = Modifier.testTag(AuthTestTags.GOOGLE_SIGN_IN)) {
              GoogleLogo()
              Spacer(Modifier.width(Dimens.spacingS))
              Text(stringResource(R.string.auth_google_sign_in))
            }
        AuthButton(
            onClick = onLogIn,
            enabled = !isLoading,
            colors = authSecondaryButtonColors(),
            modifier = Modifier.testTag(AuthTestTags.WELCOME_LOGIN)) {
              Text(stringResource(R.string.auth_sign_in_with_email))
            }
        AuthLink(
            prompt = stringResource(R.string.auth_new_here),
            action = stringResource(R.string.auth_create_account),
            onClick = onCreateAccount,
            enabled = !isLoading,
            modifier = Modifier.testTag(AuthTestTags.WELCOME_SIGNUP))
      }
}

/**
 * The Google "G" on a white disc. Google's branding asks for the logo on white, so the disc stays
 * white in dark mode as well.
 */
@Composable
private fun GoogleLogo() {
  Box(
      Modifier.size(AuthLayout.googleBadgeSize).background(Color.White, CircleShape),
      contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.google_g),
            contentDescription = null,
            modifier = Modifier.size(AuthLayout.googleLogoSize))
      }
}
