package com.yapt.planttracker.ui.screens.away

import java.time.LocalDate

internal data class PlanVisitActions(
    val onPickDay: (LocalDate) -> Unit,
    val onSetGap: (Int) -> Unit,
    val onSharePdf: () -> Unit,
    val onShareText: () -> Unit,
    val onToggleSitterPlant: (plantId: Long) -> Unit,
    val onSelectRoom: (String?) -> Unit
)

internal data class ConfirmVisitActions(
    val onToggle: (plantId: Long) -> Unit,
    val onContinue: () -> Unit,
    val onDidNotHappen: () -> Unit
)
