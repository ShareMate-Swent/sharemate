package com.android.sharemate.ui.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.android.sharemate.ui.navigation.NavigationTestTags

@Composable
fun ReceiptsScreen() {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = "Receipts",
        modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(text = "Coming soon")
  }
}
