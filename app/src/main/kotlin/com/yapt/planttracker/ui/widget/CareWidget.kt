package com.yapt.planttracker.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.yapt.planttracker.R
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayWateringGroup
import com.yapt.planttracker.ui.theme.DarkColorScheme
import com.yapt.planttracker.ui.theme.LightColorScheme
import kotlinx.coroutines.flow.first

/**
 * Home-screen widget over Care's watering queue (#923, product ADR-0088). Read-only: rows and the header
 * open the app. Data comes only from [com.yapt.planttracker.data.repository.TodayCareRepository]'s
 * default `observeQueue()` path, mapped by [careWidgetState].
 */
class CareWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as YaptApplication
        val state = careWidgetState(app.todayCareRepository.observeQueue().first())
        provideContent {
            GlanceTheme(colors = CareWidgetColors) {
                CareWidgetContent(state)
            }
        }
    }
}

class CareWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CareWidget()
}

private val CareWidgetColors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)

@Composable
private fun CareWidgetContent(state: CareWidgetState) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
    ) {
        when (state) {
            CareWidgetState.NoPlants -> CareWidgetMessage(context.getString(R.string.widget_care_first_use))
            is CareWidgetState.Watering -> {
                CareWidgetHeader(state.dueNowCount)
                if (state.dueNowCount == 0) {
                    CareWidgetMessage(context.getString(R.string.widget_care_nothing_to_water))
                }
                if (state.groups.isNotEmpty()) {
                    CareWidgetList(state.groups)
                }
            }
        }
    }
}

@Composable
private fun CareWidgetHeader(dueNowCount: Int) {
    val context = LocalContext.current
    val countText = context.resources.getQuantityString(R.plurals.widget_care_due_count, dueNowCount, dueNowCount)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable(actionStartActivity(CareWidgetIntents.openCare(context)))
            .semantics { contentDescription = context.getString(R.string.widget_care_open_care_cd, countText) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dueNowCount.toString(),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = context.resources.getQuantityString(R.plurals.widget_care_due_label, dueNowCount),
            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
            maxLines = 1
        )
    }
}

@Composable
private fun CareWidgetMessage(text: String) {
    val context = LocalContext.current
    Text(
        text = text,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(actionStartActivity(CareWidgetIntents.openCare(context))),
        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp)
    )
}

// Weighted so the list takes the height left under the header and scrolls instead of overflowing at 2×2.
@Composable
private fun ColumnScope.CareWidgetList(groups: List<CareWidgetGroup>) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
        groups.forEach { group ->
            item(itemId = group.group.headerItemId()) { CareWidgetGroupHeader(group.group) }
            items(group.rows, itemId = { it.itemId() }) { row -> CareWidgetRowItem(row) }
        }
    }
}

// Header ids are negative; row ids are positive and per task (unique even if a plant were listed twice).
private fun TodayWateringGroup.headerItemId(): Long = -1L - ordinal

private fun CareWidgetRow.itemId(): Long = (taskId.hashCode().toLong() and 0xFFFFFFFFL) + 1L

@Composable
private fun CareWidgetGroupHeader(group: TodayWateringGroup) {
    val context = LocalContext.current
    val title = when (group) {
        TodayWateringGroup.OVERDUE -> R.string.widget_care_group_overdue
        TodayWateringGroup.TODAY -> R.string.widget_care_group_today
        TodayWateringGroup.TOMORROW -> R.string.widget_care_group_tomorrow
    }
    Text(
        text = context.getString(title),
        modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

@Composable
private fun CareWidgetRowItem(row: CareWidgetRow) {
    val context = LocalContext.current
    val (taskLabel, rowDescription) = when (row.kind) {
        TodayCareKind.WATER_AND_FERTILIZE ->
            R.string.today_action_water_fertilize to R.string.care_tile_water_fertilize_cd
        else -> R.string.today_action_water to R.string.care_tile_water_cd
    }
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                actionStartActivity(CareWidgetIntents.openPlantDetail(context, row.plantId, row.plantDetailTab))
            )
            .semantics { contentDescription = context.getString(rowDescription, row.plantName) }
    ) {
        Text(
            text = row.plantName,
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1
        )
        Spacer(GlanceModifier.height(1.dp))
        Text(
            text = context.getString(taskLabel),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1
        )
    }
}
