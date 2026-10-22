package com.yapt.planttracker.ui.screens.addplant

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AddEditPlantScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(): AddEditPlantViewModel {
        val plantRepo = mockk<PlantRepository>()
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        coEvery { plantPhotoRepo.addPhoto(any()) } returns 1L
        return AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
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

    private fun str(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun saveFab_isDisplayedInInitialState() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Save").assertIsDisplayed()
    }

    @Test
    fun dormancyEditor_isOffByDefaultEvenWithoutWateringReminder() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("Dormancy window")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
    }

    @Test
    fun emptyName_showsValidationSnackbar() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Save").performClick()

        composeTestRule.onNodeWithText("Plant name is required").assertIsDisplayed()
    }

    @Test
    fun photoFab_tapped_showsPhotoSourceSheet() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performClick()

        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose from gallery").assertIsDisplayed()
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
                AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("No camera available on this device").assertIsDisplayed()
    }

    @Test
    fun takePhoto_rationaleNeeded_showsRationaleDialog() {
        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.CAMERA)
        } returns true

        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performClick()
        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("Camera permission needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera access is required to take photos of your plants.").assertIsDisplayed()
    }

    @Test
    fun takePhoto_permanentlyDenied_showsSettingsDialog() {
        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.CAMERA)
        } returns false

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
                AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performClick()
        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("Camera access denied").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Settings").assertIsDisplayed()
    }

    @Test
    fun repottingToggle_enabled_showsIntervalLabel() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("Repotting reminder").performScrollTo().assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.repottingIntervalEnabled = true }

        composeTestRule.onNodeWithText("Repot every 12 months").performScrollTo().assertIsDisplayed()
    }

    // ---- Preferred repotting seasons (#809, product ADR-0057) ----

    @Test
    fun repottingSeasonChips_areHiddenWhileTheRepottingReminderIsOff() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText(str(R.string.repotting_reminder_label)).performScrollTo().assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText(str(R.string.repotting_preferred_seasons_label))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun repottingSeasonChips_showAllFourSelectedByDefaultWhileTheReminderIsOn() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.runOnIdle { viewModel.repottingIntervalEnabled = true }

        composeTestRule.onNodeWithText(str(R.string.repotting_preferred_seasons_label)).performScrollTo()
            .assertIsDisplayed()
        listOf(R.string.season_spring, R.string.season_summer, R.string.season_autumn, R.string.season_winter)
            .forEach { season ->
                composeTestRule.onNodeWithText(str(season)).performScrollTo().assertIsSelected()
            }
    }

    @Test
    fun repottingSeasonChip_tapDeselectsIt_andTheLastOneShowsTheRepottingSnackbar() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }
        composeTestRule.runOnIdle {
            viewModel.repottingIntervalEnabled = true
            viewModel.repottingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
        }

        composeTestRule.onNodeWithText(str(R.string.season_summer)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.season_summer)).assertIsNotSelected()

        composeTestRule.onNodeWithText(str(R.string.season_spring)).performScrollTo().performClick()

        composeTestRule.onNodeWithText(str(R.string.repotting_last_season_locked_snackbar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.season_spring)).assertIsSelected()
    }

    // ---- Live watering interval (#928, product ADR-0070) ----

    private val january = LocalDate.of(2026, 1, 15)

    private fun makeEditViewModel(
        plant: Plant,
        amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD
    ): AddEditPlantViewModel {
        val plantRepo = mockk<PlantRepository>()
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude.name))
        }
        return AddEditPlantViewModel(
            plantRepo,
            mockk<PlantPhotoRepository>(),
            1L,
            dataStore,
            null,
            { Hemisphere.NORTHERN },
            { january.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        )
    }

    private fun seasonalPlant(pinned: Boolean = false) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = 7,
        wateringBaseIntervalDays = 8.0,
        pinIntervalToBase = pinned,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun wateringLabel(days: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.watering_interval_label, days)

    private fun hintText(season: Int, baseDays: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.watering_seasonal_adjustment, baseDays, str(season), baseDays)

    private fun winterHint(baseDays: Int) = hintText(R.string.season_name_winter, baseDays)

    private fun hintPrefix() = winterHint(8).substringBefore(str(R.string.season_name_winter))

    private val liveDays = SeasonalWatering.effectiveInterval(
        8.0,
        january,
        SeasonalAmplitude.STANDARD.value,
        Hemisphere.NORTHERN
    )

    @Test
    fun wateringSlider_showsTheLiveValueAndTheSeasonalHint() {
        val viewModel = makeEditViewModel(seasonalPlant())
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText(wateringLabel(liveDays)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(winterHint(8)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun seasonalHint_isHiddenOncePinned() {
        val viewModel = makeEditViewModel(seasonalPlant())
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }
        composeTestRule.onNodeWithText(winterHint(8)).performScrollTo().assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.pinIntervalToBase = true }

        composeTestRule.onNodeWithText(hintPrefix(), substring = true).assertDoesNotExist()
    }

    @Test
    fun seasonalHint_isHiddenForAPinnedPlantAndShowsTheStoredInterval() {
        val viewModel = makeEditViewModel(seasonalPlant(pinned = true))
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText(wateringLabel(7)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(hintPrefix(), substring = true).assertDoesNotExist()
    }

    @Test
    fun seasonalHint_isHiddenWhenAmplitudeIsOff() {
        val viewModel = makeEditViewModel(seasonalPlant(), SeasonalAmplitude.OFF)
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText(wateringLabel(7)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(hintPrefix(), substring = true).assertDoesNotExist()
    }

    @Test
    fun seasonalHint_isHiddenWhenTheReminderIsSwitchedOff() {
        val viewModel = makeEditViewModel(seasonalPlant())
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }
        composeTestRule.onNodeWithText(winterHint(8)).performScrollTo().assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.wateringIntervalEnabled = false }

        composeTestRule.onNodeWithText(hintPrefix(), substring = true).assertDoesNotExist()
    }

    @Test
    fun seasonalHint_isHiddenForANewPlant() {
        val viewModel = makeViewModel()
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }
        composeTestRule.runOnIdle { viewModel.wateringIntervalEnabled = true }

        composeTestRule.onNodeWithText(wateringLabel(7)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(hintPrefix(), substring = true).assertDoesNotExist()
    }
}
