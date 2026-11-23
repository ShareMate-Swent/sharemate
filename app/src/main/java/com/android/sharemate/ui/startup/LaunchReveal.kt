// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.sharemate.R

/** The approved handwriting is traced along actual strokes, not a text reveal mask. */
@Composable
fun LaunchReveal(
    progress: Int,
    failed: Boolean,
    onBarVisible: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    transition: Float = 0f,
    destinationBounds: Rect? = null,
) {
  val writing = remember { Animatable(0f) }
  val logo = remember { Animatable(0f) }
  val bar = remember { Animatable(0f) }
  val callback by rememberUpdatedState(onBarVisible)
  var sourceBounds by remember { mutableStateOf<Rect?>(null) }
  LaunchedEffect(Unit) {
    writing.animateTo(1f, tween(LaunchLayout.writingDurationMillis))
    logo.animateTo(1f, tween(LaunchLayout.logoDurationMillis))
    callback()
    bar.animateTo(1f, tween(LaunchLayout.progressDurationMillis))
  }
  Column(
      modifier = modifier.testTag("launch_reveal"),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center) {
        Box(Modifier.onGloballyPositioned { sourceBounds = it.boundsInRoot() }) {
          ShareMateBrand(
              writing = writing.value,
              logo = logo.value,
              modifier =
                  Modifier.graphicsLayer {
                        val source = sourceBounds
                        val target = destinationBounds
                        if (source != null && target != null) {
                          translationX = (target.left - source.left) * transition
                          translationY = (target.top - source.top) * transition
                        }
                      }
                      .testTag("launch_brand"))
        }
        Spacer(Modifier.height(LaunchLayout.brandToProgressGap))
        Column(
            Modifier.width(LaunchLayout.progressWidth).graphicsLayer {
              alpha = bar.value * (1f - transition * 4f).coerceAtLeast(0f)
            },
            horizontalAlignment = Alignment.CenterHorizontally) {
              LinearProgressIndicator(
                  progress =
                      progress.coerceIn(0, LaunchLayout.readyProgress) /
                          LaunchLayout.readyProgress.toFloat(),
                  modifier =
                      Modifier.fillMaxWidth()
                          .height(LaunchLayout.progressHeight)
                          .testTag("launch_progress"),
                  color = LaunchLayout.nameMint,
                  trackColor = LaunchLayout.progressTrack)
              Spacer(Modifier.height(LaunchLayout.progressGap))
              Text(
                  stringResource(R.string.launch_progress, progress),
                  color = LaunchLayout.progressLabel)
            }
        if (failed) {
          Text(
              stringResource(R.string.launch_failed),
              Modifier.padding(LaunchLayout.failurePadding),
              color = LaunchLayout.nameDark)
          TextButton(onClick = onRetry) { Text(stringResource(R.string.launch_retry)) }
        }
      }
}

/** The same artwork and dimensions are used during launch and on the welcome screen. */
@Composable
fun ShareMateBrand(
    modifier: Modifier = Modifier,
    writing: Float = 1f,
    logo: Float = 1f,
) {
  Column(
      modifier
          .widthIn(max = LaunchLayout.brandWidth)
          .fillMaxWidth()
          .height(LaunchLayout.brandHeight),
      horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.drawable.sharemate_icon_app),
            contentDescription = null,
            modifier =
                Modifier.size(LaunchLayout.logoSize)
                    .graphicsLayer {
                      alpha = logo
                      scaleX = .6f + .4f * logo
                      scaleY = scaleX
                      compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                      drawContent()
                      drawRect(
                          Brush.radialGradient(
                              0f to Color.Black,
                              .75f to Color.Black,
                              1f to Color.Transparent,
                              center = center,
                              radius = size.minDimension / 2f),
                          blendMode = BlendMode.DstIn)
                    })
        Spacer(Modifier.height(LaunchLayout.logoToNameGap))
        HandwrittenName(
            writing,
            Modifier.fillMaxWidth(LaunchLayout.nameWidthFraction)
                .widthIn(max = LaunchLayout.nameMaxWidth)
                .height(LaunchLayout.nameHeight))
      }
}

@Composable
private fun HandwrittenName(progress: Float, modifier: Modifier) {
  val measures = remember {
    strokes.map { data ->
      PathMeasure().apply { setPath(PathParser().parsePathString(data).toPath(), false) }
    }
  }
  Canvas(modifier.semantics { contentDescription = "ShareMate" }) {
    val factor = minOf(size.width / 470f, size.height / 112f)
    val total = measures.sumOf { it.length.toDouble() }.toFloat()
    var remaining = progress * total
    scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
      measures.forEachIndexed { index, measure ->
        if (remaining > 0f) {
          val path = Path()
          measure.getSegment(0f, minOf(remaining, measure.length), path, true)
          drawPath(
              path,
              if (index < 5) LaunchLayout.nameDark else LaunchLayout.nameMint,
              style = Stroke(width = 3.4f, cap = StrokeCap.Round))
        }
        remaining -= measure.length
      }
    }
  }
}

private val strokes =
    listOf(
        "M 72,28 C 79,9 48,9 32,28 C 12,52 65,46 63,66 C 61,88 20,93 18,76 C 17,67 29,62 40,64",
        "M 62,79 C 78,68 106,32 100,17 C 92,0 78,49 76,80 C 83,61 94,49 102,54 C 112,60 92,82 106,82 C 112,82 119,76 123,71",
        "M 146,58 C 134,44 119,59 120,73 C 121,91 140,78 148,56 C 145,67 139,85 151,82 C 158,80 161,75 165,69",
        "M 165,69 C 170,62 172,48 168,49 C 161,50 163,61 175,57 C 194,47 177,72 182,79 C 187,87 198,78 202,71",
        "M 202,71 C 226,67 225,49 215,53 C 202,57 196,80 211,83 C 222,85 234,74 240,67",
        "M 241,82 C 248,65 258,31 265,24 C 271,18 271,59 278,76 C 286,59 304,29 312,24 C 320,19 299,81 312,83 C 318,84 324,77 328,71",
        "M 351,58 C 339,44 324,59 325,73 C 326,91 345,78 353,56 C 350,67 344,85 356,82 C 363,80 366,75 370,69",
        "M 370,69 C 378,54 388,25 383,29 C 376,35 366,73 374,81 C 380,87 391,76 396,70",
        "M 396,70 C 420,66 419,49 409,53 C 396,57 391,80 405,83 C 417,86 431,77 440,69",
        "M 365,51 C 375,49 389,48 396,49")
