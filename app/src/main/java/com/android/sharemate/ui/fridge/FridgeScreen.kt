// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sharemate.model.item.Item
import com.android.sharemate.ui.navigation.NavigationTestTags
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun FridgeScreen() {
  Text(
      text = "Fridge",
      modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
fun FridgeScreen(viewModel: FridgeViewModel, modifier: Modifier = Modifier) {
  val items by viewModel.visibleItems.collectAsState()
  val sortOrder by viewModel.sortOrder.collectAsState()
  val selectedCategory by viewModel.selectedCategory.collectAsState()
  val selectedOwnerId by viewModel.selectedOwnerId.collectAsState()
  val categories =
      remember(items) {
        items
            .mapNotNull(Item::category)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
      }
  val owners =
      remember(items) { items.map(Item::ownerId).filter(String::isNotBlank).distinct().sorted() }

  Column(
      modifier = modifier.fillMaxSize().testTag(NavigationTestTags.PAGE_CONTENT).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Fridge", style = MaterialTheme.typography.headlineMedium)

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
          selected = sortOrder == FridgeSortOrder.EXPIRATION_DATE,
          onClick = { viewModel.setSortOrder(FridgeSortOrder.EXPIRATION_DATE) },
          label = { Text("Expiry date") },
      )
      FilterChip(
          selected = sortOrder == FridgeSortOrder.NAME,
          onClick = { viewModel.setSortOrder(FridgeSortOrder.NAME) },
          label = { Text("Name") },
      )
      ItemFilterChip(
          label = "Category",
          selectedValue = selectedCategory,
          options = categories,
          onSelected = viewModel::setCategoryFilter,
      )
      ItemFilterChip(
          label = "Owner",
          selectedValue = selectedOwnerId,
          options = owners,
          onSelected = viewModel::setOwnerFilter,
      )
    }

    if (items.isEmpty()) {
      Text(
          text = "No items match the selected filters.",
          style = MaterialTheme.typography.bodyLarge,
      )
    } else {
      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(items, key = Item::id) { item -> FridgeItem(item = item) }
      }
    }
  }
}

@Composable
private fun ItemFilterChip(
    label: String,
    selectedValue: String?,
    options: List<String>,
    onSelected: (String?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val chipLabel = selectedValue?.let { "$label: $it" } ?: label

  androidx.compose.foundation.layout.Box {
    FilterChip(
        selected = selectedValue != null,
        enabled = options.isNotEmpty() || selectedValue != null,
        onClick = { expanded = true },
        label = { Text(chipLabel) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text(if (label == "Category") "All categories" else "All owners") },
          modifier = Modifier.testTag("fridge_filter_${label.lowercase()}_all"),
          onClick = {
            onSelected(null)
            expanded = false
          },
      )
      options.forEach { option ->
        DropdownMenuItem(
            text = { Text(option) },
            modifier = Modifier.testTag("fridge_filter_${label.lowercase()}_$option"),
            onClick = {
              onSelected(option)
              expanded = false
            },
        )
      }
    }
  }
}

@Composable
fun FridgeItem(item: Item, modifier: Modifier = Modifier) {
  val expiryStatus = remember(item.expirationDate) { item.expirationDate.expiryStatus() }
  val containerColor =
      when (expiryStatus) {
        ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.errorContainer
        ExpiryStatus.SOON -> MaterialTheme.colorScheme.tertiaryContainer
        ExpiryStatus.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
      }
  val contentColor =
      when (expiryStatus) {
        ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
        ExpiryStatus.SOON -> MaterialTheme.colorScheme.onTertiaryContainer
        ExpiryStatus.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
      }

  Card(
      modifier =
          modifier.fillMaxWidth().semantics {
            contentDescription = buildString {
              append(item.name)
              item.expirationDate?.let { append(", ${it.expiryDescription(expiryStatus)}") }
            }
          },
      colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(item.name, style = MaterialTheme.typography.titleMedium, color = contentColor)
      item.category?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = contentColor)
      }
      item.expirationDate?.let { date ->
        Text(
            text = date.expiryDescription(expiryStatus),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
      }
    }
  }
}

private enum class ExpiryStatus {
  EXPIRED,
  SOON,
  NORMAL,
}

private fun java.util.Date?.expiryStatus(): ExpiryStatus {
  if (this == null) return ExpiryStatus.NORMAL

  val today = LocalDate.now()
  val expiryDate = toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
  val daysUntilExpiry = ChronoUnit.DAYS.between(today, expiryDate)
  return when {
    daysUntilExpiry < 0 -> ExpiryStatus.EXPIRED
    daysUntilExpiry <= 3 -> ExpiryStatus.SOON
    else -> ExpiryStatus.NORMAL
  }
}

private fun java.util.Date.expiryDescription(status: ExpiryStatus): String {
  val formattedDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(this)
  return when (status) {
    ExpiryStatus.EXPIRED -> "Expired on $formattedDate"
    ExpiryStatus.SOON -> "Expires soon: $formattedDate"
    ExpiryStatus.NORMAL -> "Expires $formattedDate"
  }
}
