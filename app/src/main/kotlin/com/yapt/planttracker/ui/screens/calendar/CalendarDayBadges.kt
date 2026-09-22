package com.yapt.planttracker.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.SageGreen
import java.time.LocalDate

@Composable
internal fun CalendarDayBadges(date: LocalDate, entry: DayEntry?, inMonth: Boolean, isToday: Boolean) {
    val plantCount = entry?.plants?.size ?: 0
    val dormantCount = if (isToday) entry?.dormantPlants?.size ?: 0 else 0
    if (inMonth && (plantCount > 0 || dormantCount > 0)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (plantCount > 0) DueDayBadge(date, plantCount, isToday && entry?.containsOverdue == true)
            if (dormantCount > 0) DormantDayBadge(dormantCount)
        }
    } else {
        Spacer(Modifier.size(18.dp))
    }
}

@Composable
private fun DueDayBadge(date: LocalDate, count: Int, isOverdue: Boolean) {
    val badgeColor = if (isOverdue) OverdueRed else SageGreen
    val badgeDescription = pluralStringResource(R.plurals.calendar_badge_cd, count, count)
    val overdueStateDescription = stringResource(R.string.calendar_badge_state_overdue)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(badgeColor)
            // Semantics modifiers on one node are folded tail-to-head, and
            // clearAndSetSemantics resets whatever was folded in before it (i.e.
            // anything later/more-tail in this chain). testTag must therefore come
            // before clearAndSetSemantics so it survives the reset instead of being
            // wiped by it.
            .testTag("calendar_badge_$date")
            .clearAndSetSemantics {
                contentDescription = badgeDescription
                if (isOverdue) stateDescription = overdueStateDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DormantDayBadge(count: Int) {
    val description = pluralStringResource(R.plurals.calendar_dormant_badge_cd, count, count)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
