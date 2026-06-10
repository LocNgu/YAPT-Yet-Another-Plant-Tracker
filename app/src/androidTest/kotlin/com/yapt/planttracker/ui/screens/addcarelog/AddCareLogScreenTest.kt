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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
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

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(): AddCareLogViewModel {
        val careLogRepo = mockk<CareLogRepository>()
        val plantRepo = mockk<PlantRepository>()
        val plant = Plant(id = 1L, name = "TestPlant", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 0L)
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
                onNavigateBack = {}
            )
        }

        val waterLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(CareType.WATER.labelRes())

        composeTestRule
            .onNode(hasText(waterLabel) and isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun justRightFeedbackChip_isSelectedByDefault() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        val justRightLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.feedback_just_right)

        composeTestRule
            .onNode(hasText(justRightLabel) and isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun photoButton_tapped_showsPhotoSourceSheet() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()

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
                AddCareLogScreen(viewModel = viewModel, onNavigateBack = {})
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
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = {})
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
                AddCareLogScreen(viewModel = viewModel, onNavigateBack = {})
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
