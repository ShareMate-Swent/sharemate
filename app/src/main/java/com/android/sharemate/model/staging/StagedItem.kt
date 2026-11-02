// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.staging

/**
 * An item waiting on the staging screen, before it is saved to the fridge and recorded as an
 * expense. Scanners (US-29) produce these items and the save flow (US-25) consumes them.
 *
 * @property id Identifier that is unique within one staging list.
 * @property name Product name shown to the user; never blank.
 * @property quantity Number of units; at least one.
 * @property priceCents Total price in cents, or null when the scan found no price.
 */
data class StagedItem(
    val id: String,
    val name: String,
    val quantity: Int = 1,
    val priceCents: Long? = null,
) {
  init {
    require(name.isNotBlank()) { "A staged item needs a name" }
    require(quantity > 0) { "A staged item needs a positive quantity" }
    require(priceCents == null || priceCents >= 0) { "A staged item price cannot be negative" }
  }
}
