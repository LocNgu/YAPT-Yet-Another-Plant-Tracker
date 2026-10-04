package com.yapt.planttracker.ui.screens.addcarelog

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.util.labelRes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// All fields have defaults — no validation error path exists; this test verifies the happy
// path is always reachable.
@RunWith(AndroidJUnit4::class)
class AddCareLogScreenTest {

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(initialCareType: CareType = CareType.WATER): AddCareLogViewModel {
        val careLogRepo = mockk<CareLogRepository>()
        val plantRepo = mockk<PlantRepository>()
        val plant = Plant(id = 1L, name = "TestPlant", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        return AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            careLogId = 0L
        ).also { it.preselectCareType(initialCareType) }
    }

    private fun makeEditViewModel(storedCareType: CareType): AddCareLogViewModel {
        val careLogRepo = mockk<CareLogRepository>()
        val plantRepo = mockk<PlantRepository>()
        val plant = Plant(id = 1L, name = "TestPlant", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.getLogById(99L) } returns
            CareLog(id = 99L, plantId = 1L, careType = storedCareType, loggedAt = 0L)
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
    }

    private fun careTypeLabel(careType: CareType): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(careType.labelRes())

    // Mist used to sit between Prune and Repot. A LazyRow places only the chips in view, and placed chips form
    // one contiguous run, so with Prune scrolled to the row's start and Repot placed beside it, an old Mist chip
    // between them would be placed too. performScrollToNode would only scroll the minimum, which can leave
    // Repot unplaced; an unscrolled row on a narrow or large-font device proves nothing either way.
    private fun assertPickerHasNoMistChip() {
        composeTestRule.onNodeWithTag(CARE_TYPE_PICKER_TEST_TAG)
            .performScrollToIndex(CareType.entries.indexOf(CareType.PRUNE))
        composeTestRule.onNodeWithText(careTypeLabel(CareType.PRUNE)).assertIsDisplayed()
        composeTestRule.onNodeWithText(careTypeLabel(CareType.REPOT)).assertIsDisplayed()
        composeTestRule.onNodeWithText(careTypeLabel(CareType.MIST)).assertDoesNotExist()
    }

    private fun noOpRegistryOwner(): ActivityResultRegistryOwner {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {}
        }
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun waterCareType_isSelectedByDefault() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = { _, _ -> }
            )
        }

        val waterLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(CareType.WATER.labelRes())

        composeTestRule
            .onNode(hasText(waterLabel) and isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun createMode_offersNoMistChip() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        // Misting is retired for new logs (#875, product ADR-0061).
        composeTestRule.onNodeWithText(careTypeLabel(CareType.WATER)).assertIsDisplayed()
        assertPickerHasNoMistChip()
    }

    @Test
    fun createMode_mistPreselectionFallsBackToWater() {
        val viewModel = makeViewModel(initialCareType = CareType.MIST)

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        composeTestRule
            .onNode(hasText(careTypeLabel(CareType.WATER)) and isSelected())
            .assertIsDisplayed()
        assertPickerHasNoMistChip()
    }

    @Test
    fun editingAMistLog_showsTheMistChipSelected() {
        val viewModel = makeEditViewModel(CareType.MIST)

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }

        val mistLabel = careTypeLabel(CareType.MIST)
        composeTestRule.onNodeWithTag(CARE_TYPE_PICKER_TEST_TAG)
            .performScrollToNode(hasText(mistLabel) and isSelected())
        composeTestRule.onNode(hasText(mistLabel) and isSelected()).assertIsDisplayed()
    }

    @Test
    fun editingAMistLog_chipStaysAvailableAfterSwitchingAway() {
        val viewModel = makeEditViewModel(CareType.MIST)

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }

        val mistLabel = careTypeLabel(CareType.MIST)
        val waterLabel = careTypeLabel(CareType.WATER)
        composeTestRule.onNodeWithTag(CARE_TYPE_PICKER_TEST_TAG)
            .performScrollToNode(hasText(waterLabel))
        composeTestRule.onNodeWithText(waterLabel).performClick()
        composeTestRule.onNode(hasText(waterLabel) and isSelected()).assertIsDisplayed()

        composeTestRule.onNodeWithTag(CARE_TYPE_PICKER_TEST_TAG)
            .performScrollToNode(hasText(mistLabel) and !isSelected())
        composeTestRule.onNodeWithText(mistLabel).performClick()
        composeTestRule.onNode(hasText(mistLabel) and isSelected()).assertIsDisplayed()
    }

    @Test
    fun editingANonMistLog_offersNoMistChip() {
        val viewModel = makeEditViewModel(CareType.PRUNE)

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }

        composeTestRule
            .onNode(hasText(careTypeLabel(CareType.PRUNE)) and isSelected())
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(careTypeLabel(CareType.MIST)).assertDoesNotExist()
    }

    @Test
    fun photoCareType_canBePreselected() {
        val viewModel = makeViewModel(initialCareType = CareType.PHOTO)

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = { _, _ -> }
            )
        }

        val photoLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(CareType.PHOTO.labelRes())

        composeTestRule.onNodeWithTag(CARE_TYPE_PICKER_TEST_TAG)
            .performScrollToNode(hasText(photoLabel) and isSelected())
        composeTestRule.onNode(hasText(photoLabel) and isSelected()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Take photo")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun plantNeededItFeedbackFlag_isUnselectedByDefault() {
        // #570, product ADR-0027: the 3-way soil-state chip collapsed to one optional flag, with
        // nothing pre-selected (logging without touching it writes null feedback).
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = { _, _ -> }
            )
        }

        val plantNeededItLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.care_log_feedback_plant_needed_it)

        composeTestRule
            .onNode(hasText(plantNeededItLabel, substring = true) and !isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun photoButton_tapped_showsPhotoSourceSheet() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose from gallery").assertIsDisplayed()
    }

    @Test
    fun selectingPhotoCareType_revealsInlineSourceButtons() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        // Default care type (WATER): a photo is optional, so only the compact
        // add-photo icon shows — no inline source buttons.
        composeTestRule.onNodeWithText("Take photo").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Add photo").assertExists()

        // Selecting PHOTO reveals the Take photo / Choose from gallery actions
        // inline, so the user reaches the camera/picker with no extra tap and no
        // pop-up sheet (#443).
        composeTestRule.runOnUiThread { viewModel.selectedCareType = CareType.PHOTO }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Take photo").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose from gallery").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun inlineTakePhotoButton_tapped_routesThroughCameraPermissionFlow() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        // Reveal the inline source buttons, then mock (after reveal so composition
        // is unaffected, matching the sheet-path camera tests).
        composeTestRule.runOnUiThread { viewModel.selectedCareType = CareType.PHOTO }
        composeTestRule.waitForIdle()

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.CAMERA)
        } returns true

        // Tapping the inline Take photo button drives the same shared
        // cameraState.launch() permission flow as the sheet path (#443).
        composeTestRule.onNodeWithText("Take photo").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Camera permission needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera access is required to take photos of your plants.").assertIsDisplayed()
    }

    @Test
    fun openingSheetThenSwitchingToPhoto_closesSheetWithNoOverlap() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        // Open the source sheet from a non-PHOTO care type (compact icon path).
        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()

        // Switching to PHOTO must close the sheet so only the inline buttons
        // remain — no duplicate Take photo / Choose from gallery from the sheet
        // and the inline buttons showing at once (#443).
        composeTestRule.runOnUiThread { viewModel.selectedCareType = CareType.PHOTO }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Take photo").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Choose from gallery").assertCountEquals(1)
    }

    @Test
    fun switchingAwayFromPhoto_hidesInlineSourceButtons() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        composeTestRule.runOnUiThread { viewModel.selectedCareType = CareType.PHOTO }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Take photo").performScrollTo().assertIsDisplayed()

        // Switching to a non-PHOTO care type collapses the inline buttons back to
        // the compact add-photo icon, since a photo is optional there (#443).
        composeTestRule.runOnUiThread { viewModel.selectedCareType = CareType.WATER }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Take photo").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Add photo").assertExists()
    }

    @Test
    fun takePhoto_noCameraHardware_showsSnackbar() {
        val mockPm = mockk<PackageManager>(relaxed = true)
        every { mockPm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) } returns false
        val noHardwareContext = object : ContextWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext
        ) {
            override fun getPackageManager(): PackageManager = mockPm
        }

        val viewModel = makeViewModel()
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides noHardwareContext,
                LocalActivityResultRegistryOwner provides noOpRegistryOwner()
            ) {
                AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
            }
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("No camera available on this device").assertIsDisplayed()
    }

    @Test
    fun takePhoto_rationaleNeeded_showsRationaleDialog() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }

        // Open the sheet before mocking so FilterChip composition is unaffected.
        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.CAMERA)
        } returns true

        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("Camera permission needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera access is required to take photos of your plants.").assertIsDisplayed()
    }

    @Test
    fun takePhoto_permanentlyDenied_showsSettingsDialog() {
        val testRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, false as O)
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = testRegistry
        }

        val viewModel = makeViewModel()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
            }
        }

        // Open the sheet before mocking so FilterChip composition is unaffected.
        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.CAMERA)
        } returns false

        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("Camera access denied").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Settings").assertIsDisplayed()
    }
}
