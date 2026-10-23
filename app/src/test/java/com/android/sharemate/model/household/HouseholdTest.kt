package com.android.sharemate.model.household

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.util.CustomClassMapper
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HouseholdTest {
  // Mapping Household has no @DocumentId property, so the SDK's reference context is unused.
  private val documentReference = mock(DocumentReference::class.java)

  @Test
  fun defaultAndExistingPositionalConstructorUseSharedType() {
    val createdAt = Date(1234)
    val household = Household("id", "Home", "ABCDEF", listOf("owner"), "owner", createdAt)

    assertEquals(FridgeType.SHARED, Household().type)
    assertEquals(FridgeType.SHARED, household.type)
    assertEquals(createdAt, household.createdAt)
    assertEquals("owner", household.createdBy)
  }

  @Test
  fun explicitTypeIsIndependentOfMemberCount() {
    for (type in FridgeType.entries) {
      for (members in listOf(emptyList(), listOf("owner"), listOf("owner", "member"))) {
        assertEquals(type, Household(memberIds = members, type = type).type)
      }
    }
  }

  @Test
  fun copyPreservesTypeWhenMembershipChangesAndAllowsExplicitTypeChange() {
    val household = Household(memberIds = listOf("owner"), type = FridgeType.PRIVATE)

    assertEquals(FridgeType.PRIVATE, household.copy(memberIds = listOf("owner", "member")).type)
    assertEquals(FridgeType.SHARED, household.copy(type = FridgeType.SHARED).type)
    assertEquals(FridgeType.PRIVATE, household.type)
  }

  @Test
  fun firestoreMapperRoundTripsBothTypesAsEnumNames() {
    for (type in FridgeType.entries) {
      val household = Household(id = "id", name = "Home", memberIds = listOf("owner"), type = type)
      val stored = CustomClassMapper.convertToPlainJavaTypes(household) as Map<*, *>

      assertEquals(type.name, stored["type"])
      assertEquals(
          household,
          CustomClassMapper.convertToCustomClass(stored, Household::class.java, documentReference))
    }
  }

  @Test
  fun firestoreMapperDefaultsLegacyDocumentsWithoutTypeToShared() {
    val legacy =
        mapOf(
            "id" to "legacy",
            "name" to "Home",
            "memberIds" to listOf("owner"),
            "createdBy" to "owner")

    val household =
        CustomClassMapper.convertToCustomClass(legacy, Household::class.java, documentReference)

    assertEquals(FridgeType.SHARED, household.type)
    assertEquals("legacy", household.id)
    assertEquals(listOf("owner"), household.memberIds)
  }
}
