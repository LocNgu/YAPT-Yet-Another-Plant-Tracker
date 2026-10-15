package com.yapt.planttracker.domain.model

import java.time.LocalDate

/**
 * A trip in Away mode (#895, product ADR-0065): the inclusive local days the owner is away.
 * [visitEveryDays] is the sitter rhythm chosen in trip setup, or null.
 */
data class AwayPeriod(
    val id: Long = 0,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val visitEveryDays: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class SitterVisitState {
    PLANNED,
    CONFIRMED,
    DID_NOT_HAPPEN
}

/** One plant on a visit's list; [watered] stays null until the visit is confirmed. */
data class SitterVisitPlant(
    val plantId: Long,
    val watered: Boolean? = null
)

data class SitterVisit(
    val id: Long = 0,
    val awayPeriodId: Long,
    val day: LocalDate,
    val state: SitterVisitState = SitterVisitState.PLANNED,
    val plants: List<SitterVisitPlant> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
