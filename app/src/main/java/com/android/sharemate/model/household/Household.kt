// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import java.util.Date

/**
 * Represents a household and the fridge shared by its members.
 *
 * Every fridge is a household: a private fridge is simply a household with a single member.
 *
 * @property id Unique household identifier.
 * @property name Household display name.
 * @property inviteCode Code that users can use to join the household.
 * @property memberIds User identifiers belonging to the household.
 * @property createdBy User identifier of the household creator.
 * @property createdAt Time at which the household was created.
 */
data class Household(
    val id: String = "",
    val name: String = "",
    val inviteCode: String = "",
    val memberIds: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Date? = null
)
