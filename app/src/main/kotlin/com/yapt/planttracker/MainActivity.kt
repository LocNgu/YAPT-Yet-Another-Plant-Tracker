package com.yapt.planttracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yapt.planttracker.ui.navigation.YaptNavGraph
import com.yapt.planttracker.ui.theme.YaptTheme
import com.yapt.planttracker.worker.ReminderScheduler
import com.yapt.planttracker.worker.ReminderWorker

class MainActivity : ComponentActivity() {

    private var initialPlantId by mutableStateOf<Long?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ReminderScheduler.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            initialPlantId = intent.getLongExtra(ReminderWorker.EXTRA_PLANT_ID, 0L).takeIf { it != 0L }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderScheduler.schedule(this)
        }

        val app = application as YaptApplication

        setContent {
            YaptTheme {
                YaptNavGraph(
                    app = app,
                    initialPlantId = initialPlantId,
                    onDeepLinkConsumed = { initialPlantId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialPlantId = intent.getLongExtra(ReminderWorker.EXTRA_PLANT_ID, 0L).takeIf { it != 0L }
    }
}
