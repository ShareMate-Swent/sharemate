// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.*
import org.junit.Test

class PurchaseReviewTest {
  @Test
  fun unavailableMembersPreserveDraftAndBlockOnlyPurchaseUntilFreshSnapshot() {
    val vm = model()
    vm.toggleParticipant("b")
    val original = vm.confirmedPurchase()
    vm.updatePurchaseMembers(emptyMap(), available = false)
    assertEquals("a", vm.state.value.purchase?.paidBy)
    assertEquals(setOf("b"), vm.state.value.purchase?.participants)
    assertEquals(members, vm.state.value.purchase?.members)
    assertNull(vm.confirmedPurchase())
    assertNotNull(vm.confirmedItems())
    vm.updatePurchaseMembers(members, available = false)
    assertNull(vm.confirmedPurchase())
    vm.updatePurchaseMembers(members)
    assertEquals(original, vm.confirmedPurchase())
    vm.updatePurchaseMembers(emptyMap(), available = false)
    vm.updatePurchaseMembers(members - "b")
    assertTrue(vm.state.value.purchase?.membershipChanged == true)
    assertTrue(vm.state.value.purchase?.participants?.isEmpty() == true)
    assertNull(vm.confirmedPurchase())
  }

  @Test
  fun freshEmptySnapshotClearsSelectionsAfterListenerRecovery() {
    val vm = model()
    vm.toggleParticipant("b")
    vm.updatePurchaseMembers(members, available = false)
    vm.updatePurchaseMembers(emptyMap())
    assertNull(vm.state.value.purchase?.paidBy)
    assertTrue(vm.state.value.purchase?.participants?.isEmpty() == true)
    assertNull(vm.confirmedPurchase())
  }

  @Test
  fun firstMemberSnapshotDefaultsPayerWithoutResettingLaterSelections() {
    val vm = StagingViewModel(emptyList(), PurchaseReview.forUser("a", emptyMap()))
    vm.updatePurchaseMembers(mapOf("a" to "Alice", "b" to "Bob"))
    assertEquals("a", vm.state.value.purchase?.paidBy)
    vm.selectPayer("b")
    vm.updatePurchaseMembers(mapOf("a" to "Alice", "b" to "Bob"))
    assertEquals("b", vm.state.value.purchase?.paidBy)
    vm.updatePurchaseMembers(emptyMap())
    vm.updatePurchaseMembers(mapOf("a" to "Alice", "b" to "Bob"))
    assertNull(vm.state.value.purchase?.paidBy)
    assertTrue(vm.state.value.purchase?.membershipChanged == true)
  }

  private val members = mapOf("c" to "Chris", "a" to "Alex", "b" to "Blair")

  private fun item(cents: Long? = 1000L, id: String = "row") =
      StagedItem(id, "Food", quantity = 2, priceCents = cents)

  private fun model(items: List<StagedItem> = listOf(item())) =
      StagingViewModel(items, PurchaseReview.forUser("a", members))

  @Test
  fun anotherPayerCanPayForSelectedParticipants() {
    val vm = model()
    vm.toggleParticipant("b")
    vm.selectPayer("b")
    assertEquals("b", vm.confirmedPurchase()?.paidBy)
    assertNotNull(vm.confirmedItems())
    assertEquals("b", vm.state.value.purchase?.paidBy)
    vm.selectPayer("a")
    assertEquals("a", vm.confirmedPurchase()?.paidBy)
    assertEquals(mapOf("b" to 1000L), vm.confirmedPurchase()?.shares)
  }

  @Test
  fun missingOrUnknownSignedInUserCannotConfirmBySelectingMember() {
    for (user in listOf(null, "", " ", "outsider")) {
      val vm = StagingViewModel(listOf(item()), PurchaseReview.forUser(user, members))
      vm.selectPayer("a")
      vm.toggleParticipant("b")
      assertNull(vm.confirmedPurchase())
      assertEquals(PurchaseReviewError.SUBMITTER_NOT_MEMBER, vm.state.value.purchasePreview?.error)
      assertNotNull(vm.confirmedItems())
    }
  }

  @Test
  fun defaultsToCurrentUserButDoesNotChargeAnyoneWithoutSelection() {
    val vm = model()
    assertEquals("a", vm.state.value.purchase?.paidBy)
    assertEquals(PurchaseReviewError.NO_PARTICIPANTS, vm.state.value.purchasePreview?.error)
    assertNull(vm.confirmedPurchase())
    vm.toggleParticipant("b")
    assertEquals(mapOf("b" to 1000L), vm.confirmedPurchase()?.shares)
    assertEquals("a", vm.confirmedPurchase()?.paidBy)
  }

  @Test
  fun remainderUsesUidOrderAndRowTotalsAreNotMultipliedByQuantity() {
    val vm = model()
    listOf("c", "b", "a").forEach(vm::toggleParticipant)
    val result = checkNotNull(vm.confirmedPurchase())
    assertEquals(1000L, result.totalCents)
    assertEquals(mapOf("a" to 334L, "b" to 333L, "c" to 333L), result.shares)
    assertEquals(result.totalCents, result.shares.values.sum())
    vm.toggleParticipant("a")
    assertEquals(mapOf("b" to 500L, "c" to 500L), vm.confirmedPurchase()?.shares)
  }

  @Test
  fun centsSmallerThanParticipantCountRemainExact() {
    val review = PurchaseReview(members, "a", setOf("c", "a", "b"), currentUserId = "a")
    assertEquals(mapOf("a" to 1L, "b" to 0L, "c" to 0L), review.preview(listOf(item(1))).shares)
  }

  @Test
  fun unknownPricesBlockAllConfirmation() {
    val vm = model(listOf(item(null)))
    vm.toggleParticipant("a")
    assertFalse(vm.state.value.canConfirm)
    assertNull(vm.confirmedItems())
    assertNull(vm.confirmedPurchase())
    assertEquals(PurchaseReviewError.MISSING_PRICE, vm.state.value.purchasePreview?.error)
    vm.startEditing("row")
    vm.updateDraftPrice("4,80")
    vm.saveDraft()
    assertEquals(480L, vm.confirmedPurchase()?.totalCents)
  }

  @Test
  fun zeroOverflowAndServerLimitAreRejected() {
    val review = PurchaseReview(members, "a", setOf("b"), currentUserId = "a")
    assertEquals(PurchaseReviewError.NONPOSITIVE_TOTAL, review.preview(listOf(item(0))).error)
    assertEquals(PurchaseReviewError.NONPOSITIVE_TOTAL, review.preview(emptyList()).error)
    assertEquals(
        PurchaseReviewError.TOTAL_OVERFLOW,
        review.preview(listOf(item(Long.MAX_VALUE), item(1, "other"))).error,
    )
    val limit = PurchaseReview.MAX_PURCHASE_CENTS
    for (total in listOf(limit - 1, limit)) assertNull(review.preview(listOf(item(total))).error)
    assertEquals(PurchaseReviewError.TOTAL_TOO_LARGE, review.preview(listOf(item(limit + 1))).error)
  }

  @Test
  fun payerMustBeCurrentMemberAndCanBeChangedIndependently() {
    val vm = model()
    vm.toggleParticipant("b")
    vm.selectPayer("c")
    assertNull(vm.state.value.purchasePreview?.error)
    assertEquals("c", vm.confirmedPurchase()?.paidBy)
    vm.selectPayer("outsider")
    vm.toggleParticipant("outsider")
    assertNull(vm.state.value.purchasePreview?.error)
    assertEquals(setOf("b"), vm.state.value.purchase?.participants)
    assertEquals(
        PurchaseReviewError.INVALID_PAYER,
        PurchaseReview.forUser("outsider", members).preview(listOf(item())).error,
    )
  }

  @Test
  fun membershipRemovalRequiresExplicitReviewAndDoesNotChargeNewMembers() {
    val vm = model()
    vm.toggleParticipant("b")
    vm.toggleParticipant("c")
    vm.updatePurchaseMembers(mapOf("a" to "Alex", "b" to "Blair", "d" to "Dana"))
    assertEquals(setOf("b"), vm.state.value.purchase?.participants)
    assertEquals(PurchaseReviewError.MEMBERSHIP_CHANGED, vm.state.value.purchasePreview?.error)
    assertNull(vm.confirmedPurchase())
    vm.selectPayer("a")
    assertEquals(mapOf("b" to 1000L), vm.confirmedPurchase()?.shares)
    vm.updatePurchaseMembers(mapOf("b" to "Blair"))
    assertNull(vm.state.value.purchase?.paidBy)
    assertNull(vm.confirmedPurchase())
    vm.selectPayer("b")
    assertEquals(PurchaseReviewError.SUBMITTER_NOT_MEMBER, vm.state.value.purchasePreview?.error)
  }

  @Test
  fun editsDeletionAndOpenDraftRecomputeFinancialReadiness() {
    val vm = model(listOf(item(100), item(200, "second")))
    vm.toggleParticipant("b")
    vm.startEditing("row")
    assertNull(vm.confirmedPurchase())
    vm.updateDraftPrice("2.50")
    vm.saveDraft()
    assertEquals(450L, vm.confirmedPurchase()?.totalCents)
    vm.deleteItem("second")
    assertEquals(250L, vm.confirmedPurchase()?.totalCents)
    vm.startAdding()
    vm.updateDraftName("Milk")
    vm.saveDraft()
    assertNull(vm.confirmedPurchase())
    vm.cancelEditing()
    assertEquals(250L, vm.confirmedPurchase()?.totalCents)
    vm.deleteItem("row")
    assertNull(vm.confirmedPurchase())
  }

  @Test
  fun inventoryOnlyModeAndInvalidInjectedParticipantsAreSafe() {
    val vm = StagingViewModel(listOf(item()))
    vm.selectPayer("a")
    vm.toggleParticipant("a")
    vm.updatePurchaseMembers(members)
    assertNotNull(vm.confirmedItems())
    assertNull(vm.confirmedPurchase())
    assertEquals(
        PurchaseReviewError.INVALID_PARTICIPANTS,
        PurchaseReview(members, "a", setOf("outsider"), currentUserId = "a")
            .preview(listOf(item()))
            .error,
    )
    assertNull(PurchaseReview.forUser(null, members).paidBy)
  }

  @Test
  fun blankAndUnknownPayersAndParticipantsAreRejected() {
    for (payer in listOf(null, "", " ", "outsider")) {
      val preview = PurchaseReview(members, payer, setOf("a")).preview(listOf(item()))
      assertEquals(PurchaseReviewError.INVALID_PAYER, preview.error)
      assertTrue(preview.shares.isEmpty())
    }
    val preview =
        PurchaseReview(members, "a", setOf(" "), currentUserId = "a").preview(listOf(item()))
    assertEquals(PurchaseReviewError.INVALID_PARTICIPANTS, preview.error)
    assertTrue(preview.shares.isEmpty())
    val review = PurchaseReview.forUser(" ", members + (" " to "Invalid"))
    assertNull(review.paidBy)
    assertEquals(members, review.members)
  }

  @Test
  fun membershipSnapshotsFilterBlankIdsAndPreserveReviewRequirement() {
    val current = members + (" " to "Invalid")
    val review = PurchaseReview(current, " ", setOf(" ", "b", "outsider")).withMembers(current)
    assertEquals(members, review.members)
    assertNull(review.paidBy)
    assertEquals(setOf("b"), review.participants)
    assertTrue(review.membershipChanged)
    val refreshed = review.withMembers(members)
    assertTrue(refreshed.membershipChanged)
    assertNull(refreshed.paidBy)
    assertEquals(setOf("b"), refreshed.participants)
    val unselected = PurchaseReview(members, null).withMembers(members)
    assertNull(unselected.paidBy)
    assertFalse(unselected.membershipChanged)
    assertTrue(unselected.participants.isEmpty())
  }

  @Test
  fun unchangedMembershipKeepsSelectionAndParticipantReviewCanRecover() {
    val vm = model()
    vm.toggleParticipant("b")
    vm.updatePurchaseMembers(members)
    assertEquals(mapOf("b" to 1000L), vm.confirmedPurchase()?.shares)
    vm.updatePurchaseMembers(members - "b")
    assertNull(vm.confirmedPurchase())
    vm.toggleParticipant("c")
    val confirmed = checkNotNull(vm.confirmedPurchase())
    assertEquals("a", confirmed.paidBy)
    assertEquals(mapOf("c" to 1000L), confirmed.shares)
    assertEquals(listOf(item()), confirmed.items)
  }
}
