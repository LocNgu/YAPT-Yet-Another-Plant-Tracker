package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt

/**
 * Accessibility labels for a [SteppedSlider], bundled into one value so the composable stays
 * within Detekt's `LongParameterList` threshold (#531, product ADR-0048 — same bundling pattern as
 * this codebase's other "config" data classes, e.g. `IntervalSetting`). [decreaseContentDescription]/
 * [increaseContentDescription] must name the setting (e.g. "Decrease watering interval"), never a
 * generic "Decrease"/"Increase" shared across settings. [stateDescription], when non-null, is applied
 * to the slider node so a screen reader announces the current human-readable value (e.g.
 * "Every 7 days") after a change, not just its numeric position.
 */
data class SteppedSliderLabels(
    val decreaseContentDescription: String,
    val increaseContentDescription: String,
    val stateDescription: String? = null
)

/**
 * Change callbacks for a [SteppedSlider], bundled alongside [SteppedSliderLabels] to keep the
 * composable's own parameter count within Detekt's `LongParameterList` threshold. See
 * [SteppedSlider]'s doc for what "finished" means for a drag vs. a stepper-button tap.
 *
 * [onValueChangeFinished]'s `viaButtonTap` parameter (#531 review round 1, product ADR-0048) tells a
 * caller whether this particular "finished" call came from releasing the slider thumb (`false`) or
 * tapping a −/+ button (`true`) — a caller that wants to treat the two differently (e.g. committing a
 * release immediately but coalescing a burst of taps) can branch on it; one that doesn't care (the
 * common case) simply ignores the parameter.
 */
data class SteppedSliderCallbacks(
    val onValueChange: (Int) -> Unit,
    val onValueChangeFinished: ((viaButtonTap: Boolean) -> Unit)? = null
)

/**
 * A Material 3 [Slider] flanked by a leading "decrease" and trailing "increase" [IconButton]
 * (#531, product ADR-0048) — an integer-valued track long enough (e.g. 58 or 178 steps) that a
 * single pixel of drag covers more than one unit is hard to land on an exact value by dragging
 * alone; the buttons give an exact ±1 nudge alongside the slider's coarse-grained drag.
 *
 * Purely controlled: [value] always reflects what is shown, and every change — drag or button tap
 * — is reported through [callbacks]'s `onValueChange`, already coerced into [range].
 * `onValueChangeFinished` is the commit hook: `null` (the Add/Edit Plant usage) means
 * `onValueChange` alone is the persistence path, so a caller with nothing extra to do on commit can
 * omit it; a non-null value (the Plant Detail inline cards and the dormancy cadence control) is
 * invoked on slider release *and* on every ±1 button tap, since a caller using this parameter
 * expects "commit now" to mean the same thing regardless of which input produced the change — its
 * `viaButtonTap` parameter lets a caller that *does* want to distinguish the two do so (see
 * [SteppedSliderCallbacks]). A caller that wants commit-on-release-only slider dragging plus
 * immediate-or-coalesced-commit taps (as the Plant Detail inline cards do) keeps its own local
 * `value` state, passing it in here and only touching its persisted state from
 * `onValueChangeFinished`.
 *
 * `steps` is always `range.last - range.first - 1`, computed here so callers stop hand-computing it
 * (and can't disagree with each other on the formula).
 *
 * Haptics: a light [HapticFeedbackType.SegmentTick] tick fires when a drag moves the *rounded*
 * value to a new integer — tracked via an internal remembered "last ticked value" that resyncs
 * ([LaunchedEffect]) whenever [value] changes from outside a drag (a button tap, or the caller's own
 * state changing independently, #531 review round 1) so a drag starting right after such a change is
 * compared against the right baseline rather than a stale one left over from an earlier drag. Button
 * taps deliberately do not trigger this tick — [IconButton] already gives its own touch feedback, and
 * a discrete, deliberate tap doesn't need a second confirmation the way a continuous drag does.
 */
@Composable
fun SteppedSlider(
    value: Int,
    range: IntRange,
    callbacks: SteppedSliderCallbacks,
    labels: SteppedSliderLabels,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var lastTickedValue by remember(range) { mutableIntStateOf(value) }
    LaunchedEffect(value) { lastTickedValue = value }

    fun changeBy(delta: Int) {
        val next = (value + delta).coerceIn(range.first, range.last)
        lastTickedValue = next
        callbacks.onValueChange(next)
        callbacks.onValueChangeFinished?.invoke(true)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { changeBy(-1) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = labels.decreaseContentDescription)
        }
        val sliderModifier = Modifier.weight(1f).let {
            val nodeStateDescription = labels.stateDescription
            if (nodeStateDescription != null) {
                it.semantics { stateDescription = nodeStateDescription }
            } else {
                it
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val rounded = raw.roundToInt().coerceIn(range.first, range.last)
                if (rounded != lastTickedValue) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    lastTickedValue = rounded
                }
                callbacks.onValueChange(rounded)
            },
            onValueChangeFinished = { callbacks.onValueChangeFinished?.invoke(false) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = sliderModifier
        )
        IconButton(onClick = { changeBy(1) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = labels.increaseContentDescription)
        }
    }
}
