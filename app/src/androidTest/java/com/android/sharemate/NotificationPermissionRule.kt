// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal fun notificationPermissionRule(): TestRule =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = base
      }
    }
