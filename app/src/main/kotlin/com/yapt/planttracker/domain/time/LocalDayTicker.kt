package com.yapt.planttracker.domain.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

class LocalDayTicker(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val delayUntilNextEmission: suspend (Long) -> Unit = { delay(it) }
) {

    val dates: Flow<LocalDate> = flow {
        while (true) {
            val now = clock.instant().atZone(clock.zone)
            emit(now.toLocalDate())
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(clock.zone)
            val delayMillis = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
            delayUntilNextEmission(delayMillis)
        }
    }
}
