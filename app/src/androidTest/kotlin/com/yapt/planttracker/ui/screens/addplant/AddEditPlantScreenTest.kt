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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
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
    fun mistingToggle_enabled_showsIntervalLabel() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        // Disabled: the row shows the generic reminder label (scroll it into view — it sits near
        // the bottom of the scrollable form, below watering and fertilizing).
        composeTestRule.onNodeWithText("Misting reminder").performScrollTo().assertIsDisplayed()

        // Enabling the toggle is exactly `mistingIntervalEnabled = it`; drive that state directly
        // (avoids asserting on Row/Column node topology) and verify the interval label is revealed.
        composeTestRule.runOnIdle { viewModel.mistingIntervalEnabled = true }

        composeTestRule.onNodeWithText("Mist every 7 days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun repottingToggle_enabled_showsIntervalLabel() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("Repotting reminder").performScrollTo().assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.repottingIntervalEnabled = true }

        composeTestRule.onNodeWithText("Repot every 365 days").performScrollTo().assertIsDisplayed()
    }
}
