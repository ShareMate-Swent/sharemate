// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the behaviour and accessibility of the shared redesign components. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CommonComponentsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val calls = mutableListOf<String>()

  private fun show(content: @Composable () -> Unit) =
      compose.setContent { ShareMateTheme { Column { content() } } }

  private fun navItem(label: String, selected: Boolean = false) =
      FloatingNavItem(label, Icons.Filled.Home, selected, "nav_$label") { calls += label }

  /** Checks the area that receives taps, which can be larger than the visible shape. */
  private fun assertTouchTarget(tag: String) {
    val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
    val minimum = with(compose.density) { 48.dp.toPx() }
    assertTrue("Touch target $bounds is too small", bounds.height >= minimum - 1)
    assertTrue("Touch target $bounds is too small", bounds.width >= minimum - 1)
  }

  @Test
  fun buttonsClickOnlyWhenEnabled() {
    show {
      AppPrimaryButton("Save", { calls += "save" })
      AppPrimaryButton("Off", { calls += "off" }, enabled = false)
      AppSecondaryButton("Later", { calls += "later" })
      AppSecondaryButton("Never", { calls += "never" }, enabled = false)
    }
    listOf("Save", "Off", "Later", "Never").forEach { compose.onNodeWithText(it).performClick() }
    assertEquals(listOf("save", "later"), calls)
  }

  @Test
  fun chipExposesItsStateAndKeepsATouchTarget() {
    show {
      AppChip("All", selected = true, onClick = { calls += "all" }, Modifier.testTag("all"))
      AppChip("Fruits", selected = false, onClick = { calls += "fruits" }, Modifier.testTag("f"))
      AppChip("Off", selected = true, onClick = { calls += "off" }, enabled = false)
    }
    compose.onNodeWithTag("all").assertIsSelected().performClick()
    compose.onNodeWithTag("f").assertIsNotSelected().performClick()
    assertTouchTarget("f")
    compose.onNodeWithText("Off").assertIsNotEnabled()
    assertEquals(listOf("all", "fruits"), calls)
  }

  @Test
  fun tagShowsItsText() {
    show { AppTag("In 2 days", ShareMateTheme.statusColors.expiringSoon) }
    compose.onNodeWithText("In 2 days").assertIsDisplayed()
  }

  @Test
  fun avatarInitialHandlesCaseSpacesBlankAndEmoji() {
    assertEquals("A", initialOf("  alex"))
    assertEquals("É", initialOf("élodie"))
    assertEquals("?", initialOf("   "))
    assertEquals("🍎", initialOf("🍎 apple"))
  }

  @Test
  fun avatarIsHiddenFromScreenReaders() {
    show { LetterAvatar("Alex", Modifier.testTag("avatar")) }
    compose.onNodeWithTag("avatar").assertExists()
    compose.onNodeWithText("A").assertDoesNotExist()
  }

  @Test
  fun listRowShowsItsPartsAndClicksOnlyWithAHandler() {
    show {
      AppCard {
        AppListRow(
            title = "Apples",
            subtitle = "6 · You",
            onClick = { calls += "row" },
            leading = { Text("lead") },
            trailing = { Text("Nov 30") },
            modifier = Modifier.testTag("row"))
        AppListRow(title = "Static", modifier = Modifier.testTag("static"))
      }
    }
    listOf("Apples", "6 · You", "lead", "Nov 30").forEach {
      compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
    }
    compose.onNodeWithTag("row").assertHasClickAction().performClick()
    compose.onNodeWithTag("static").assertHasNoClickAction()
    assertEquals(listOf("row"), calls)
  }

  @Test
  fun listRowKeepsTrailingVisibleWithALongTitle() {
    show {
      AppListRow(
          title = "A very long item name ".repeat(10),
          modifier = Modifier.width(240.dp),
          trailing = { Text("Nov 30") })
    }
    compose.onNodeWithText("Nov 30").assertIsDisplayed()
  }

  @Test
  fun navBarShowsOnlyTheSelectedLabelAndReportsTaps() {
    show {
      FloatingNavBar(
          items = listOf(navItem("Fridge", selected = true), navItem("Recipes"), navItem("Chat")),
          action = FloatingNavAction("Add item", "nav_add") { calls += "add" })
    }
    compose.onNodeWithText("Fridge", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithText("Recipes", useUnmergedTree = true).assertDoesNotExist()
    compose
        .onNodeWithTag("nav_Recipes")
        .assertContentDescriptionEquals("Recipes")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        .assertIsNotSelected()
        .performClick()
    assertTouchTarget("nav_Recipes")
    compose.onNodeWithTag("nav_Fridge").assertIsSelected().performClick()
    compose.onNodeWithTag("nav_add").assertContentDescriptionEquals("Add item").performClick()
    assertEquals(listOf("Recipes", "Fridge", "add"), calls)
  }

  @Test
  fun navBarPutsTheActionInTheMiddle() {
    show {
      FloatingNavBar(
          items = listOf(navItem("A", true), navItem("B"), navItem("C"), navItem("D")),
          action = FloatingNavAction("Add", "nav_add") {})
    }
    val b = compose.onNodeWithTag("nav_B").getUnclippedBoundsInRoot()
    val add = compose.onNodeWithTag("nav_add").getUnclippedBoundsInRoot()
    val c = compose.onNodeWithTag("nav_C").getUnclippedBoundsInRoot()
    assertTrue(b.right <= add.left && add.right <= c.left)
  }

  @Test
  @Config(qualifiers = "w280dp-h600dp")
  fun navBarKeepsEveryItemOnANarrowScreenWithALongLabel() {
    show {
      FloatingNavBar(
          items =
              listOf(
                  navItem("A very long destination name", true),
                  navItem("B"),
                  navItem("C"),
                  navItem("D")),
          action = FloatingNavAction("Add", "nav_add") {})
    }
    listOf("nav_B", "nav_add", "nav_C", "nav_D").forEach {
      compose.onNodeWithTag(it).assertIsDisplayed()
    }
    val root = compose.onRoot().getUnclippedBoundsInRoot()
    val last = compose.onNodeWithTag("nav_D").getUnclippedBoundsInRoot()
    assertTrue(last.right <= root.right)
  }
}
