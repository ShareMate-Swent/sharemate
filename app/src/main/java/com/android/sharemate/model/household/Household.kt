// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import java.util.Date

/**
 * Represents a household and its private or shared fridge.
 *
 * The fridge type is explicit and independent of the current member count. The type alone does not
 * enforce access; authorization must be enforced by security rules.
 *
 * @property id Unique household identifier.
 * @property name Household display name.
 * @property inviteCode Code for joining shared households; empty for newly created private fridges.
 * @property memberIds User identifiers belonging to the household.
 * @property createdBy User identifier of the household creator.
 * @property createdAt Time at which the household was created.
 * @property type Fridge type, defaulting to shared for existing households without a stored type.
 */
data class Household(
    val id: String = "",
    val name: String = "",
    val inviteCode: String = "",
    val memberIds: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Date? = null,
    val type: FridgeType = FridgeType.SHARED
)
