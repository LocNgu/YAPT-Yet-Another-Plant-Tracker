package com.yapt.planttracker.ui.components

import android.graphics.RectF
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.core.cartesian.layer.MutableCartesianLayerDimensions
import com.patrykandpatrick.vico.core.common.Point
import com.patrykandpatrick.vico.core.common.Size
import com.patrykandpatrick.vico.core.common.data.CacheStore
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises real Vico 2.5.2 `Zoom` primitives (`vico/core/.../Zoom.kt`, `VicoZoomState.kt` — the
 * pinned version in `app/build.gradle.kts`) rather than re-deriving the geometry by hand, to verify
 * the root cause of "still cut off around November" (#628 follow-up to #621): the seasonal curve
 * preview chart is unscrollable and expects to always show all 12 months, but
 * `rememberVicoZoomState(zoomEnabled = false)`'s *default* `initialZoom` is
 * `Zoom.max(Zoom.fixed(), Zoom.Content)` — the larger of a hardcoded 1.0x floor and the
 * fit-to-bounds value. Our `LineCartesianLayer` sets no `pointProvider`, so its un-zoomed base
 * `xSpacing` is exactly `Defaults.POINT_SPACING` (32dp, verified in `core/common/Defaults.kt`) per
 * x-unit (`LineCartesianLayer.updateDimensions()`) — 12 months is therefore ~384dp wide un-zoomed.
 * On any container narrower than that, the fit-to-bounds value drops below 1.0, so `Zoom.max` picks
 * the 1.0 floor instead of shrinking further — pinning the real content width above the container's
 * and, since scroll is disabled, silently overflowing past the right edge (where Nov/Dec sit),
 * hard-clipped by the Composable's own bounds. `Zoom.Content` alone has no such floor.
 *
 * Simplification: these fakes leave `unscalableStartPadding`/`unscalableEndPadding` at 0 (the state
 * before `HorizontalAxis.updateLayerDimensions()` contributes label-driven padding) since `Zoom
 * .Content`'s formula only reads `unscalablePadding`/`scalablePadding`/`xSpacing`, and the axis's own
 * contribution there is tens of px — negligible next to the ~300dp+ deficit demonstrated below. That
 * omission doesn't change the qualitative conclusion under test: whether the 1.0 floor activates.
 */
@RunWith(RobolectricTestRunner::class)
class SeasonalCurveZoomFitTest {

    private val density = 2.75f

    private class FakeRanges(
        override val minX: Double,
        override val maxX: Double,
        override val xStep: Double,
    ) : CartesianChartRanges {
        override fun getYRange(axisPosition: Axis.Position.Vertical?): CartesianChartRanges.YRange =
            error("not needed for Zoom.getValue")
    }

    private fun fakeContext(ranges: CartesianChartRanges, density: Float): CartesianMeasuringContext =
        object : CartesianMeasuringContext {
            override val model: CartesianChartModel get() = error("not needed for Zoom.getValue")
            override val ranges: CartesianChartRanges = ranges
            override val scrollEnabled: Boolean = false
            override val zoomEnabled: Boolean = false
            override val layerPadding: CartesianLayerPadding = CartesianLayerPadding()

            @Deprecated("Use `markerX`.", ReplaceWith("markerX"))
            override val pointerPosition: Point? = null
            override val markerX: Double? = null
            override val markerSeriesIndex: Int? = null
            override val canvasSize: Size get() = error("not needed for Zoom.getValue")

            @Deprecated("Use `canvasSize`.", ReplaceWith("canvasSize"))
            override val canvasBounds: RectF get() = error("not needed for Zoom.getValue")
            override val density: Float = density
            override val extraStore: ExtraStore get() = error("not needed for Zoom.getValue")

            override fun spToPx(sp: Float): Float = sp * density
            override val isLtr: Boolean = true
            override val cacheStore: CacheStore get() = error("not needed for Zoom.getValue")
        }

    /** Mirrors `LineCartesianLayer.updateDimensions()` with no `pointProvider` (our chart's config). */
    private fun baseLayerDimensions(density: Float) =
        MutableCartesianLayerDimensions(xSpacing = POINT_SPACING_DP * density)

    private fun monthRanges() = FakeRanges(minX = 0.0, maxX = MONTHS_IN_YEAR.toDouble() - 0.001, xStep = 1.0)

    @Test
    fun `narrow bounds - default zoom floor keeps base width, overflowing the container`() {
        val ranges = monthRanges()
        val context = fakeContext(ranges, density)
        val layerDimensions = baseLayerDimensions(density)
        // A narrow plant-detail card: 300dp, well under the ~384dp un-zoomed base content width.
        val boundsWidthPx = 300f * density
        val bounds = RectF(0f, 0f, boundsWidthPx, 140f * density)

        val contentFitZoom = Zoom.Content.getValue(context, layerDimensions, bounds)
        val defaultInitialZoom = Zoom.max(Zoom.fixed(), Zoom.Content).getValue(context, layerDimensions, bounds)

        assertTrue(
            "fit-to-width zoom should be below 1.0 on a narrow container (content is wider than it)",
            contentFitZoom < 1f,
        )
        assertEquals(
            "Zoom.max(fixed(1.0), Content) must pick the 1.0 floor over the narrower fit-to-width value",
            1f,
            defaultInitialZoom,
            0.0001f,
        )

        val realContentWidthAtDefaultZoom = contentWidthAtZoom(layerDimensions, ranges, defaultInitialZoom)
        assertTrue(
            "default zoom's real content width must exceed the container width to reproduce the clipping bug",
            realContentWidthAtDefaultZoom > boundsWidthPx,
        )

        val realContentWidthAtFixedZoom = contentWidthAtZoom(layerDimensions, ranges, contentFitZoom)
        assertEquals(
            "Zoom.Content's own content width must equal the container width by construction (the fix)",
            boundsWidthPx,
            realContentWidthAtFixedZoom,
            0.5f,
        )
    }

    @Test
    fun `wide bounds - default zoom and Content-only zoom agree since both already fit`() {
        val ranges = monthRanges()
        val context = fakeContext(ranges, density)
        val layerDimensions = baseLayerDimensions(density)
        // A wide container, comfortably above the ~384dp un-zoomed base content width.
        val boundsWidthPx = 500f * density
        val bounds = RectF(0f, 0f, boundsWidthPx, 140f * density)

        val contentFitZoom = Zoom.Content.getValue(context, layerDimensions, bounds)
        val defaultInitialZoom = Zoom.max(Zoom.fixed(), Zoom.Content).getValue(context, layerDimensions, bounds)

        assertTrue(
            "fit-to-width zoom should exceed 1.0 once the container is wider than the un-zoomed base content",
            contentFitZoom > 1f,
        )
        assertEquals(contentFitZoom, defaultInitialZoom, 0.0001f)
    }

    private fun contentWidthAtZoom(
        layerDimensions: MutableCartesianLayerDimensions,
        ranges: CartesianChartRanges,
        zoom: Float,
    ): Float = layerDimensions.xSpacing * zoom * (ranges.xLength / ranges.xStep).toFloat()

    private companion object {
        const val POINT_SPACING_DP = 32f // `Defaults.POINT_SPACING`, verified in Vico 2.5.2 source
    }
}
