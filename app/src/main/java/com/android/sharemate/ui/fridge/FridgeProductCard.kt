// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.ImageLoader
import coil.imageLoader
import com.android.sharemate.R
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.Dimens

/**
 * Compact inventory tile reusing the shared image renderer and attribution components.
 *
 * @param expiryStatus how close [item] is to its expiration date, already worked out.
 */
@Composable
fun FridgeItem(
    item: Item,
    expiryStatus: ExpiryStatus,
    modifier: Modifier = Modifier,
    removalEnabled: Boolean = false,
    onRemove: () -> Unit = {},
    editEnabled: Boolean = false,
    onEdit: () -> Unit = {},
    statusEnabled: Boolean = false,
    onStatusChange: (ItemStatus) -> Unit = {},
    imageLoader: ImageLoader = LocalContext.current.imageLoader,
    ownership: ItemOwnership,
    personalPhotos: PersonalPhotoController? = null,
) {
  val sharingLabel =
      stringResource(
          if (ownership.sharing == ItemSharing.SHARED) R.string.fridge_shared
          else R.string.fridge_personal)
  val ownerLabel = ownerLabel(ownership.name, ownership.isCurrentUser)
  val ownerDescription = stringResource(R.string.fridge_item_owner, ownerLabel)
  val expiryColor =
      when (expiryStatus) {
        ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.error
        ExpiryStatus.SOON -> MaterialTheme.colorScheme.tertiary
        ExpiryStatus.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  val expiryText =
      item.expirationDate?.let {
        stringResource(R.string.fridge_card_expiration, ExpirationDates.formatCompact(it))
      }
  val expiryDescription =
      expiryText?.let {
        when (expiryStatus) {
          ExpiryStatus.EXPIRED -> stringResource(R.string.fridge_card_expired, it)
          ExpiryStatus.SOON -> stringResource(R.string.fridge_card_expiring_soon, it)
          ExpiryStatus.NORMAL -> it
        }
      }
  Column(
      modifier =
          modifier.fillMaxWidth().testTag(C.Tag.fridge_item_prefix + item.id).semantics {
            contentDescription =
                listOfNotNull(item.name, sharingLabel, ownerDescription, expiryDescription)
                    .joinToString(", ")
          },
      verticalArrangement = Arrangement.spacedBy(FridgeLayout.productContentSpacing)) {
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(FridgeLayout.productImageAspectRatio)
                    .testTag(C.Tag.fridgeProductSquare(item.id)),
            shape = RoundedCornerShape(FridgeLayout.productCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant) {
              Box(contentAlignment = Alignment.Center) {
                FridgeItemImage(item, imageLoader, Modifier.fillMaxSize(), personalPhotos)
                Box(Modifier.align(Alignment.TopEnd)) {
                  ItemActions(
                      item,
                      editEnabled,
                      onEdit,
                      removalEnabled,
                      onRemove,
                      statusEnabled,
                      onStatusChange)
                }
              }
            }
        val ownershipColor =
            if (ownership.sharing == ItemSharing.SHARED) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.primary
        Surface(
            modifier = Modifier.align(Alignment.End),
            shape = RoundedCornerShape(FridgeLayout.ownershipBadgeCornerPercent),
            border = BorderStroke(Dimens.borderWidth, ownershipColor),
            color = MaterialTheme.colorScheme.surface,
            contentColor = ownershipColor) {
              Text(
                  sharingLabel,
                  modifier =
                      Modifier.padding(horizontal = FridgeLayout.ownershipLabelSpacing)
                          .testTag(C.Tag.fridge_sharing_badge_prefix + item.id),
                  style = MaterialTheme.typography.labelSmall)
            }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = FridgeLayout.productTextMaxLines,
            overflow = TextOverflow.Ellipsis)
        Text(
            ownerDescription,
            modifier = Modifier.testTag(C.Tag.fridge_owner_name_prefix + item.id),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (ownership.isCurrentUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        expiryText?.let {
          Text(
              it,
              color = expiryColor,
              style = MaterialTheme.typography.bodySmall,
              maxLines = FridgeLayout.productTextMaxLines,
              overflow = TextOverflow.Ellipsis)
        }
        Text(
            pluralStringResource(R.plurals.fridge_card_quantity, item.quantity, item.quantity),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = FridgeLayout.productTextMaxLines,
            overflow = TextOverflow.Ellipsis)
      }
}

/** Presentation-only formatting; no identifiers or email fallback. */
@Composable
internal fun ownerLabel(name: String?, currentUser: Boolean): String =
    when {
      name != null && currentUser -> stringResource(R.string.fridge_owner_you_named, name)
      name != null -> name
      currentUser -> stringResource(R.string.fridge_owner_you)
      else -> stringResource(R.string.fridge_item_owner_unknown)
    }

@Composable
private fun ItemActions(
    item: Item,
    editEnabled: Boolean,
    onEdit: () -> Unit,
    removalEnabled: Boolean,
    onRemove: () -> Unit,
    statusEnabled: Boolean,
    onStatusChange: (ItemStatus) -> Unit,
) {
  var expanded by remember(item.id) { mutableStateOf(false) }
  var showCredits by remember(item.id) { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current
  val editDescription = stringResource(R.string.fridge_edit_description, item.name)
  val removeDescription = stringResource(R.string.fridge_remove_description, item.name)
  IconButton(
      onClick = { expanded = true },
      modifier = Modifier.testTag(C.Tag.fridgeItemActions(item.id))) {
        Icon(Icons.Default.MoreVert, stringResource(R.string.fridge_card_actions, item.name))
      }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    if (item.image?.origin == ItemImageOrigin.REMOTE_IMAGE) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.fridge_card_image_details)) },
          onClick = {
            expanded = false
            showCredits = true
          })
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.fridge_edit_item)) },
        enabled = editEnabled,
        modifier =
            Modifier.testTag(C.Tag.fridge_edit_prefix + item.id).semantics {
              contentDescription = editDescription
            },
        onClick = {
          expanded = false
          onEdit()
        })
    DropdownMenuItem(
        text = { Text(stringResource(R.string.fridge_remove)) },
        enabled = removalEnabled,
        modifier =
            Modifier.testTag(C.Tag.fridge_remove_prefix + item.id).semantics {
              contentDescription = removeDescription
            },
        onClick = {
          expanded = false
          onRemove()
        })
    if (item.status == ItemStatus.ACTIVE) {
      listOf(
              ItemStatus.EATEN to R.string.fridge_eaten,
              ItemStatus.DISCARDED to R.string.fridge_discarded)
          .forEach { (status, resource) ->
            val label = stringResource(resource)
            val description = stringResource(R.string.fridge_status_description, item.name, label)
            DropdownMenuItem(
                text = { Text(label) },
                enabled = statusEnabled,
                modifier =
                    Modifier.testTag(
                            (if (status == ItemStatus.EATEN) C.Tag.fridge_eaten_prefix
                            else C.Tag.fridge_discard_prefix) + item.id)
                        .semantics { contentDescription = description },
                onClick = {
                  expanded = false
                  onStatusChange(status)
                })
          }
    }
  }
  if (showCredits) {
    AlertDialog(
        onDismissRequest = { showCredits = false },
        title = { Text(stringResource(R.string.fridge_card_image_details)) },
        text = {
          CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            FridgeImageCredits(item.image)
          }
        },
        confirmButton = {
          TextButton(onClick = { showCredits = false }) {
            Text(stringResource(R.string.fridge_card_close))
          }
        })
  }
}
