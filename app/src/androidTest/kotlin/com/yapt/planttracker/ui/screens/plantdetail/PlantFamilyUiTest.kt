package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.theme.YaptTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Propagate tab's Family card, plant picker and post-log offer (#960, product ADR-0073). Assertions
 * are on user-visible text and on what each action reports back, never on tree structure (#420).
 */
class PlantFamilyUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    private class Recorder {
        val opened = mutableListOf<Long>()
        var linkParent = 0
        var linkExisting = 0
        var addNew = 0
        var unlinkedParent = 0
        val unlinkedChildren = mutableListOf<Long>()

        fun actions() = FamilyCardActions(
            onOpenPlant = { opened += it },
            onLinkParent = { linkParent++ },
            onLinkExisting = { linkExisting++ },
            onAddNew = { addNew++ },
            onUnlinkParent = { unlinkedParent++ },
            onUnlinkChild = { unlinkedChildren += it }
        )
    }

    private fun showCard(state: FamilyCardState, recorder: Recorder) {
        composeTestRule.setContent {
            YaptTheme { FamilyCard(state, recorder.actions()) }
        }
    }

    @Test
    fun familyCard_withoutParent_offersLinkParentAndEmptyChildren() {
        val recorder = Recorder()
        showCard(FamilyCardState(parent = null, children = emptyList()), recorder)

        composeTestRule.onNodeWithText(string(R.string.family_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_no_children)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_link_parent)).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(string(R.string.family_link_existing)).performClick()
        composeTestRule.onNodeWithText(string(R.string.family_add_new)).performClick()

        assertEquals(1, recorder.linkParent)
        assertEquals(1, recorder.linkExisting)
        assertEquals(1, recorder.addNew)
    }

    @Test
    fun familyCard_withParentAndChildren_showsThemAndOpensTheTappedOne() {
        val recorder = Recorder()
        showCard(
            FamilyCardState(parent = plant(7L, "Mother"), children = listOf(plant(8L, "Cutting A"), plant(9L, "Cutting B"))),
            recorder
        )

        composeTestRule.onNodeWithText(string(R.string.family_propagated_from)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_link_parent)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Cutting A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mother").performClick()
        composeTestRule.onNodeWithText("Cutting B").performClick()

        assertEquals(listOf(7L, 9L), recorder.opened)
    }

    @Test
    fun familyCard_labelsArchivedAndHiddenRelatives() {
        val recorder = Recorder()
        showCard(
            FamilyCardState(
                parent = plant(7L, "Mother").copy(archivedAt = 5L),
                children = listOf(plant(8L, "Cutting").copy(hiddenAt = 6L))
            ),
            recorder
        )

        composeTestRule.onNodeWithText(string(R.string.family_relative_archived, "Mother")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_relative_hidden, "Cutting")).assertIsDisplayed()
    }

    @Test
    fun familyCard_longPressOffersUnlink_forParentAndChild() {
        val recorder = Recorder()
        showCard(FamilyCardState(parent = plant(7L, "Mother"), children = listOf(plant(8L, "Cutting"))), recorder)

        composeTestRule.onNodeWithText("Mother").performTouchInput { longClick() }
        composeTestRule.onNodeWithText(string(R.string.family_unlink)).performClick()
        composeTestRule.onNodeWithText("Cutting").performTouchInput { longClick() }
        composeTestRule.onNodeWithText(string(R.string.family_unlink)).performClick()

        assertEquals(1, recorder.unlinkedParent)
        assertEquals(listOf(8L), recorder.unlinkedChildren)
    }

    @Test
    fun familyCard_unlinkIsAlsoAnAccessibilityAction() {
        val recorder = Recorder()
        showCard(FamilyCardState(parent = null, children = listOf(plant(8L, "Cutting"))), recorder)

        val actions = composeTestRule.onNodeWithText("Cutting").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == string(R.string.family_unlink) })
        composeTestRule.runOnUiThread {
            assertTrue(actions.first { it.label == string(R.string.family_unlink) }.action())
        }

        assertEquals(listOf(8L), recorder.unlinkedChildren)
    }

    private fun candidates() = listOf(
        FamilyCandidate(plant(1L, "Aloe").copy(room = "Kitchen"), currentParent = null),
        FamilyCandidate(plant(2L, "Basil").copy(species = "Ocimum"), currentParent = plant(9L, "Old mother")),
        FamilyCandidate(plant(3L, "Cactus").copy(archivedAt = 4L), currentParent = null)
    )

    private fun showPicker(mode: FamilyPickerMode, list: List<FamilyCandidate>?, onSelect: (Long) -> Unit = {}) {
        composeTestRule.setContent {
            YaptTheme { FamilyPlantPickerSheet(mode, list, onSelect = onSelect, onDismiss = {}) }
        }
    }

    @Test
    fun picker_listsCandidates_marksArchived_andShowsTheCurrentParent() {
        showPicker(FamilyPickerMode.CHILD, candidates())

        composeTestRule.onNodeWithText(string(R.string.family_picker_title_child)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Aloe").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_relative_archived, "Cactus")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.family_picker_currently_from, "Old mother")).assertIsDisplayed()
    }

    @Test
    fun picker_searchFiltersByNameSpeciesAndRoom() {
        showPicker(FamilyPickerMode.PARENT, candidates())

        composeTestRule.onNodeWithTag(FAMILY_PICKER_SEARCH_TEST_TAG).performTextInput("kitch")
        composeTestRule.onNodeWithText("Aloe").assertIsDisplayed()
        composeTestRule.onNodeWithText("Basil").assertDoesNotExist()

        composeTestRule.onNodeWithTag(FAMILY_PICKER_SEARCH_TEST_TAG).performTextInput("zzz")
        composeTestRule.onNodeWithText(string(R.string.family_picker_no_match)).assertIsDisplayed()
    }

    @Test
    fun picker_choosingAPlantReportsItsId() {
        var chosen: Long? = null
        showPicker(FamilyPickerMode.PARENT, candidates()) { chosen = it }

        composeTestRule.onNodeWithText("Basil").performClick()

        assertEquals(2L, chosen)
    }

    @Test
    fun picker_withNothingToLink_saysSo() {
        showPicker(FamilyPickerMode.PARENT, emptyList())

        composeTestRule.onNode(hasText(string(R.string.family_picker_empty))).assertIsDisplayed()
    }

    @Test
    fun offerDialog_offersNewExistingAndNotNow() {
        var result = ""
        composeTestRule.setContent {
            YaptTheme {
                PropagateOfferDialog(
                    plantName = "Pothos",
                    onNewPlant = { result += "new;" },
                    onExistingPlant = { result += "existing;" },
                    onDismiss = { result += "dismiss;" }
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.propagate_offer_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.propagate_offer_message, "Pothos")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.propagate_offer_new)).performClick()
        composeTestRule.onNodeWithText(string(R.string.propagate_offer_existing)).performClick()
        composeTestRule.onNodeWithText(string(R.string.propagate_offer_not_now)).performClick()

        assertEquals("new;existing;dismiss;", result)
    }
}
