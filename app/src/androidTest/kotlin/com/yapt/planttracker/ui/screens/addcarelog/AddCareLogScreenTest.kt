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
import androidx.compose.ui.test.isHeading
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

    private fun makeViewModel(storedCareType: CareType = CareType.WATER): AddCareLogViewModel {
        val careLogRepo = mockk<CareLogRepository>()
        val plantRepo = mockk<PlantRepository>()
        val plant = Plant(id = 1L, name = "TestPlant", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.getLogById(99L) } returns
            CareLog(id = 99L, plantId = 1L, careType = storedCareType, loggedAt = 0L)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
    }

    private fun showScreen(viewModel: AddCareLogViewModel) {
        composeTestRule.setContent {
            AddCareLogScreen(viewModel = viewModel, onNavigateBack = { _, _ -> })
        }
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }
    }

    private fun careTypeLabel(careType: CareType): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(careType.labelRes())

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
    fun editScreen_showsTheEditTitle() {
        showScreen(makeViewModel())

        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.care_log_title_edit)
        ).assertIsDisplayed()
    }

    private fun assertShowsTypeHeader(type: CareType) {
        showScreen(makeViewModel(type))

        composeTestRule.onNode(hasText(careTypeLabel(type)) and isHeading()).assertIsDisplayed()
    }

    @Test
    fun editingAWaterLog_showsWaterHeader() = assertShowsTypeHeader(CareType.WATER)

    @Test
    fun editingANoteLog_showsNoteHeader() = assertShowsTypeHeader(CareType.NOTE)

    @Test
    fun editingACustomLog_showsCustomHeader() = assertShowsTypeHeader(CareType.CUSTOM)

    @Test
    fun editingACheckLog_showsCheckHeader() = assertShowsTypeHeader(CareType.CHECK)

    @Test
    fun editingAWaterLog_offersNoOtherCareTypes() {
        showScreen(makeViewModel(CareType.WATER))

        CareType.entries.filter { it != CareType.WATER }.forEach { other ->
            composeTestRule.onNodeWithText(careTypeLabel(other)).assertDoesNotExist()
        }
    }

    @Test
    fun editingAMistLog_showsMistHeaderWithoutAChipRow() {
        showScreen(makeViewModel(CareType.MIST))

        composeTestRule.onNode(hasText(careTypeLabel(CareType.MIST)) and isHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithText(careTypeLabel(CareType.WATER)).assertDoesNotExist()
        composeTestRule.onNodeWithText(careTypeLabel(CareType.PRUNE)).assertDoesNotExist()
    }

    @Test
    fun editingAPhotoLog_revealsInlineSourceButtons() {
        showScreen(makeViewModel(CareType.PHOTO))

        composeTestRule.onNode(hasText(careTypeLabel(CareType.PHOTO)) and isHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Take photo").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose from gallery").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun plantNeededItFeedbackFlag_isUnselectedByDefault() {
        // #570, product ADR-0027: the 3-way soil-state chip collapsed to one optional flag, with
        // nothing pre-selected (logging without touching it writes null feedback).
        showScreen(makeViewModel())

        val plantNeededItLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.care_log_feedback_plant_needed_it)

        composeTestRule
            .onNode(hasText(plantNeededItLabel, substring = true) and !isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun photoButton_tapped_showsPhotoSourceSheet() {
        showScreen(makeViewModel())

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose from gallery").assertIsDisplayed()
    }

    @Test
    fun inlineTakePhotoButton_tapped_routesThroughCameraPermissionFlow() {
        showScreen(makeViewModel(CareType.PHOTO))

        // Mock after the inline source buttons are composed so composition is unaffected,
        // matching the sheet-path camera tests.

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
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }

        composeTestRule.onNodeWithContentDescription("Add photo").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Take photo").performClick()

        composeTestRule.onNodeWithText("No camera available on this device").assertIsDisplayed()
    }

    @Test
    fun takePhoto_rationaleNeeded_showsRationaleDialog() {
        showScreen(makeViewModel())

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
        composeTestRule.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { viewModel.isLoaded }

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
