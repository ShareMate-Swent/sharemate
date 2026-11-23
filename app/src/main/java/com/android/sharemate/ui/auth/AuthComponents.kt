// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens
import com.android.sharemate.ui.theme.ShareMateTheme

/** Round back button at the top of the log in and sign up forms. */
@Composable
internal fun AuthBackButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
  IconButton(
      onClick = onClick,
      enabled = enabled,
      colors =
          IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surface,
              contentColor = MaterialTheme.colorScheme.onSurface),
      modifier = modifier.size(AuthLayout.backButtonSize)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.auth_back))
      }
}

/**
 * A text field with its label above it, as in the redesign: a rounded field on the surface color
 * with a thin border that turns to the accent color when focused.
 *
 * @param label Shown above the field.
 * @param placeholder Shown inside the empty field.
 * @param supportingText Shown under the field, like a password rule.
 * @param fieldModifier Modifier of the field itself, where test tags go.
 */
@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    fieldModifier: Modifier,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
    // The field itself carries the label for screen readers, so the visible one is not read twice.
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clearAndSetSemantics {})
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = fieldModifier.fillMaxWidth().semantics { contentDescription = label })
    supportingText?.let {
      Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Colors of the main call to action: the accent color. */
@Composable internal fun authPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors()

/** Colors of the secondary call to action: a white card-like button. */
@Composable
internal fun authSecondaryButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface)

/** A full-width button of the entry flow, with the design system's rounded shape. */
@Composable
internal fun AuthButton(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: ButtonColors,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      colors = colors,
      shape = MaterialTheme.shapes.medium,
      modifier = modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight),
      content = content)
}

/**
 * A footer link such as "New here? **Create an account**": a muted [prompt] followed by the bold,
 * underlined [action]. The whole line is one button.
 */
@Composable
internal fun AuthLink(
    prompt: String,
    action: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  val promptColor = MaterialTheme.colorScheme.onSurfaceVariant
  val actionColor = MaterialTheme.colorScheme.onBackground
  TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
    Text(
        buildAnnotatedString {
          withStyle(SpanStyle(color = promptColor)) { append(prompt) }
          append(" ")
          withStyle(
              SpanStyle(
                  color = actionColor,
                  fontWeight = FontWeight.Bold,
                  textDecoration = TextDecoration.Underline)) {
                append(action)
              }
        },
        style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * The welcome screen's illustration: a white fridge standing in front of pastel shapes in the badge
 * colors of the theme. Drawn in Compose so it follows the dark theme. Decorative.
 */
@Composable
internal fun WelcomeIllustration(modifier: Modifier = Modifier) {
  val status = ShareMateTheme.statusColors
  Box(
      modifier.fillMaxWidth().height(AuthLayout.illustrationHeight),
      contentAlignment = Alignment.Center) {
        Blob(
            MaterialTheme.colorScheme.primaryContainer,
            CircleShape,
            AuthLayout.blobLarge,
            Alignment.CenterStart,
            x = Dimens.spacingS,
            y = -Dimens.spacingL)
        Blob(
            status.expiringSoon.container,
            RoundedCornerShape(Dimens.cornerRadiusL),
            AuthLayout.blobLarge,
            Alignment.TopEnd,
            x = -Dimens.spacingS)
        Blob(
            status.shared.container,
            CircleShape,
            AuthLayout.blobLarge,
            Alignment.BottomEnd,
            x = -Dimens.spacingXl,
            y = -Dimens.spacingL)
        Blob(
            status.expired.container,
            RoundedCornerShape(Dimens.cornerRadiusM),
            AuthLayout.blobSmall,
            Alignment.BottomStart,
            x = Dimens.spacingXl,
            y = -Dimens.spacingXl)
        Fridge()
      }
}

@Composable
private fun BoxScope.Blob(
    color: Color,
    shape: Shape,
    size: Dp,
    alignment: Alignment,
    x: Dp = 0.dp,
    y: Dp = 0.dp,
) {
  Box(Modifier.align(alignment).offset(x = x, y = y).size(size).background(color, shape))
}

@Composable
private fun Fridge() {
  val shape = RoundedCornerShape(AuthLayout.fridgeCornerRadius)
  Surface(
      shape = shape,
      color = MaterialTheme.colorScheme.surface,
      // Keeps the fridge apart from the background in dark mode, where the shadow does not show.
      border = BorderStroke(Dimens.borderWidth, MaterialTheme.colorScheme.outlineVariant),
      modifier =
          Modifier.width(AuthLayout.fridgeWidth)
              .height(AuthLayout.fridgeHeight)
              .shadow(AuthLayout.fridgeElevation, shape)) {
        Column {
          Box(Modifier.fillMaxWidth().height(AuthLayout.freezerHeight)) {
            Handle(AuthLayout.freezerHandleLength, Modifier.align(Alignment.CenterStart))
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Box(Modifier.fillMaxWidth().padding(top = Dimens.spacingL)) {
            Handle(AuthLayout.fridgeHandleLength, Modifier.align(Alignment.TopStart))
          }
        }
      }
}

@Composable
private fun Handle(length: Dp, modifier: Modifier) {
  Box(
      modifier
          .padding(start = AuthLayout.handleInset)
          .width(AuthLayout.handleWidth)
          .height(length)
          .background(MaterialTheme.colorScheme.outline, CircleShape))
}
