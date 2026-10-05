package com.android.sharemate.ui.receipts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.android.sharemate.ui.navigation.NavigationTestTags

@Composable
fun ReceiptsScreen() {
  Text(
      text = "Receipts",
      modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.primary,
  )
}
