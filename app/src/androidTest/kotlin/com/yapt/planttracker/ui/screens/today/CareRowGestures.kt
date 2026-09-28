package com.yapt.planttracker.ui.screens.today

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput

// A merged Care row owns the clickable, but its bounds also contain the action buttons (stacked under
// the name on a 320dp screen), which consume a centre tap. Touch the row's left content padding
// instead: it is inside the row's hit area and never covered by a child.
private const val ROW_EDGE_INSET_PX = 4f

internal fun SemanticsNodeInteraction.tapRowEdge(): SemanticsNodeInteraction =
    performTouchInput { click(centerLeft + Offset(ROW_EDGE_INSET_PX, 0f)) }
