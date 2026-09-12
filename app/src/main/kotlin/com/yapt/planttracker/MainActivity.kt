package com.yapt.planttracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yapt.planttracker.data.preferences.SettingsDefaults
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.notification.PostWateringReminderPresentation
import com.yapt.planttracker.ui.components.PostWateringReminderDialog
import com.yapt.planttracker.ui.navigation.Screen
import com.yapt.planttracker.ui.navigation.YaptNavGraph
import com.yapt.planttracker.ui.theme.ThemeMode
import com.yapt.planttracker.ui.theme.YaptTheme
import com.yapt.planttracker.worker.ReminderScheduler
import com.yapt.planttracker.worker.ReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var initialPlantId by mutableStateOf<Long?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scheduleReminderFromPrefs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            initialPlantId = deepLinkPlantId(intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scheduleReminderFromPrefs()
        }

        val app = application as YaptApplication

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsDataStore.data
                    .map { it[SettingsKeys.KEEP_SCREEN_ON] ?: false }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }

        setContent { YaptContent(app) }
    }

    @Suppress("FunctionNaming") // Compose entry point; MainActivity sits outside Detekt's ui/** exemption.
    @Composable
    private fun YaptContent(app: YaptApplication) {
        val themeModeFlow = remember {
            app.settingsDataStore.data.map { prefs ->
                runCatching { ThemeMode.valueOf(prefs[SettingsKeys.THEME_MODE] ?: "") }
                    .getOrDefault(ThemeMode.SYSTEM)
            }
        }
        val themeMode by themeModeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
        val pendingPromptFlow = remember {
            PostWateringReminderPresentation.pendingPrompt(app.settingsDataStore)
        }
        val pendingPromptAt by pendingPromptFlow.collectAsStateWithLifecycle(initialValue = null)
        val scope = rememberCoroutineScope()
        val darkTheme = when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        YaptTheme(darkTheme = darkTheme) {
            YaptNavGraph(
                app = app,
                initialPlantId = initialPlantId,
                onDeepLinkConsumed = { initialPlantId = null }
            )
            pendingPromptAt?.let { triggeredAt ->
                PostWateringReminderDialog(
                    onDismiss = {
                        scope.launch {
                            PostWateringReminderPresentation.dismiss(
                                this@MainActivity,
                                app.settingsDataStore,
                                triggeredAt
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as YaptApplication).setAppForeground(true)
    }

    override fun onStop() {
        (application as YaptApplication).setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialPlantId = deepLinkPlantId(intent)
    }

    private fun deepLinkPlantId(intent: Intent): Long? =
        if (intent.getBooleanExtra(PostWateringReminderPresentation.EXTRA_SHOW_CARED_TODAY, false)) {
            Screen.PlantList.CARED_TODAY_DEEP_LINK_ID
        } else {
            intent.getLongExtra(ReminderWorker.EXTRA_PLANT_ID, 0L).takeIf { it != 0L }
        }

    private fun scheduleReminderFromPrefs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = settingsDataStore.data.first()
            val enabled = prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
            if (!enabled) return@launch
            val hour = prefs[SettingsKeys.REMINDER_HOUR] ?: SettingsDefaults.REMINDER_HOUR
            val minute = prefs[SettingsKeys.REMINDER_MINUTE] ?: SettingsDefaults.REMINDER_MINUTE
            ReminderScheduler.schedule(this@MainActivity, hour, minute)
        }
    }
}
