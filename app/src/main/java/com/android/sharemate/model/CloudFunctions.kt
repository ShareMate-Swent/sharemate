// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model

/** Cloud Functions shared by every feature that calls a callable. */
object CloudFunctions {
  /** Region hosting every callable; keep in sync with `functions/index.js`. */
  const val REGION = "europe-west6"
}
