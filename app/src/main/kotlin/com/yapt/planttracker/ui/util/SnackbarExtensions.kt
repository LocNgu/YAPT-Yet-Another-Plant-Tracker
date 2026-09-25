package com.yapt.planttracker.ui.util

import androidx.compose.material3.SnackbarHostState

/**
 * Shows [message] unless a snackbar with that exact text is already visible. Backs the "at least
 * one season must stay active" warning (#813) shared by Add/Edit Plant and Plant Detail's Fertilize
 * tab — a burst of taps on `FertilizingSeasonsSelector`'s locked last chip (or a
 * `PlantDetailViewModel.Event.FertilizingSeasonToggleRejected` racing in right after) fires this
 * once per tap, but must not queue repeat copies of the same text back-to-back. A different message
 * already showing is unaffected — it still enqueues normally through [SnackbarHostState].
 */
suspend fun SnackbarHostState.showSnackbarOnce(message: String) {
    if (currentSnackbarData?.visuals?.message == message) return
    showSnackbar(message)
}
