package com.yapt.planttracker.domain.devmode

/**
 * Pure, Compose-free tap-counter logic for the Settings -> About version row's
 * developer-mode unlock gesture. The counter itself is screen-scoped state owned by the
 * caller (a plain `remember { mutableIntStateOf(0) }` in SettingsScreen) so it resets
 * whenever Settings leaves composition, with no wall-clock timeout.
 */
sealed class DeveloperModeTapOutcome {
    data object Silent : DeveloperModeTapOutcome()
    data class Countdown(val tapsRemaining: Int) : DeveloperModeTapOutcome()
    data object Unlocked : DeveloperModeTapOutcome()
    data object Inert : DeveloperModeTapOutcome()
}

data class DeveloperModeTapResult(
    val newTapCount: Int,
    val outcome: DeveloperModeTapOutcome
)

object DeveloperModeUnlock {
    const val REQUIRED_TAPS = 5
    private const val COUNTDOWN_START_TAP = 3

    /**
     * Registers one tap on the version row.
     *
     * @param currentTapCount the caller's screen-scoped tap count before this tap
     * @param isDeveloperModeEnabled the persisted developer-mode state; once true, every
     *   further tap is [DeveloperModeTapOutcome.Inert] (no counter change, no feedback)
     */
    fun registerTap(currentTapCount: Int, isDeveloperModeEnabled: Boolean): DeveloperModeTapResult {
        if (isDeveloperModeEnabled) {
            return DeveloperModeTapResult(currentTapCount, DeveloperModeTapOutcome.Inert)
        }
        val newCount = currentTapCount + 1
        return when {
            newCount >= REQUIRED_TAPS ->
                DeveloperModeTapResult(0, DeveloperModeTapOutcome.Unlocked)
            newCount >= COUNTDOWN_START_TAP ->
                DeveloperModeTapResult(newCount, DeveloperModeTapOutcome.Countdown(REQUIRED_TAPS - newCount))
            else ->
                DeveloperModeTapResult(newCount, DeveloperModeTapOutcome.Silent)
        }
    }
}
