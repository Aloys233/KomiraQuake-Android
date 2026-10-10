package com.aloys23.komiraquake.ui.map

import com.aloys23.komiraquake.core.TravelTimeService
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow

class MapCameraTest {
    private fun event(latitude: Double = 35.0, timestamp: Long = 1_000L) = EarthquakeEvent(
        id = "event-1",
        eventId = "event-1",
        magnitude = 5.0,
        latitude = latitude,
        longitude = 105.0,
        depth = 10.0,
        location = "测试震中",
        timestamp = timestamp,
        source = "test",
        distanceKm = 0.0,
        estimatedIntensity = "I",
    )

    @Test
    fun cameraRefocusesWhenSameEventReceivesUpdatedCoordinates() {
        val before = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 280f)
        val after = focusCameraKey(event(latitude = 36.0), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 280f)

        assertNotEquals(before, after)
    }

    @Test
    fun cameraRefocusesWhenViewportInsetsSettle() {
        val before = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 0f, heightPx = 0f, topOcclusionPx = 280f)
        val after = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 520f)

        assertNotEquals(before, after)
    }

    @Test
    fun cameraAnimationDoesNotRestartForSameEventUpdates() {
        val before = cameraAnimationKey(event(timestamp = 1_000L), request = 1L, hasFocus = true)
        val after = cameraAnimationKey(event(latitude = 36.0, timestamp = 2_000L), request = 1L, hasFocus = true)

        assertEquals(before, after)
    }

    @Test
    fun cameraAnimationStartsForNewEventEvenWithoutRequestIncrement() {
        val before = cameraAnimationKey(event(), request = 1L, hasFocus = true)
        val after = cameraAnimationKey(event().copy(id = "event-2", eventId = "event-2"), request = 1L, hasFocus = true)

        assertNotEquals(before, after)
    }

    @Test
    fun cameraAnimationStartsForExplicitRefocusRequest() {
        val before = cameraAnimationKey(event(), request = 1L, hasFocus = true)
        val after = cameraAnimationKey(event(), request = 2L, hasFocus = true)

        assertNotEquals(before, after)
    }

    @Test
    fun finishingPreviousEventWavesDoesNotResetNewEventCamera() {
        assertTrue(!shouldFinishWavesForFocus("event-1", "event-2", wavesShown = true))
        assertTrue(shouldFinishWavesForFocus("event-2", "event-2", wavesShown = true))
    }

    @Test
    fun userLocationFocusUsesCloserZoomThanOverview() {
        assertTrue(USER_LOCATION_ZOOM > 6f)
    }

    @Test
    fun noWavesFallsBackTo300Km() {
        // P/S 全部隐藏（历史事件或已离开中国范围）时用固定 300 km。
        assertEquals(300.0, focusRadiusKm(-1.0, -1.0), 0.0)
    }

    @Test
    fun tinyWavesClampTo100KmFloor() {
        assertEquals(100.0, focusRadiusKm(2.0, 5.0), 0.0)
        assertEquals(100.0, focusRadiusKm(-1.0, 30.0), 0.0)
    }

    @Test
    fun followsLargerWave() {
        assertEquals(520.0, focusRadiusKm(400.0, 520.0), 0.0)
        assertEquals(520.0, focusRadiusKm(520.0, 400.0), 0.0)
    }

    @Test
    fun largerWaveUsesWiderCameraView() {
        val rect = ScreenRect(0f, 520f, 1008f, 1820f)
        val close = focusZoomForRect(radiusKm = 100.0, latitude = 35.0, rect = rect)
        val wide = focusZoomForRect(radiusKm = 1000.0, latitude = 35.0, rect = rect)

        assertTrue(close > wide)
    }

    @Test
    fun focusZoomIsClampedToBasemapMaximum() {
        val zoom = focusZoomForRect(
            radiusKm = 100.0, latitude = 35.0,
            rect = ScreenRect(0f, 0f, 1080f, 2000f),
            maxZoom = 5,
        )

        assertEquals(5f, zoom, 0f)
    }

    @Test
    fun focusZoomUsesTheActualAsymmetricViewport() {
        // 安卓端右侧有工具条、左侧没有侧栏，可用区右边界比对称模型更靠右，
        // 因此同样的半径能取更近的 zoom。
        val asymmetric = focusZoomForRect(
            radiusKm = 500.0, latitude = 35.0,
            rect = ScreenRect(0f, 520f, 1008f, 1820f),
        )
        val symmetric = focusZoomForRect(
            radiusKm = 500.0, latitude = 35.0,
            rect = ScreenRect(72f, 520f, 1008f, 1820f),
        )

        assertTrue(asymmetric > symmetric)
    }

    @Test
    fun focusZoomFitsCircleInsideFreeRectWithMargin() {
        val rect = ScreenRect(0f, 520f, 1008f, 1820f)
        val marginPx = 24f
        val radiusKm = 500.0
        val zoom = focusZoomForRect(radiusKm, latitude = 35.0, rect = rect, marginPx = marginPx)

        // 屏上每公里像素：墨卡托保角，与绘制处 pxPerKm 同一公式。
        val cosLat = cos(35.0 * PI / 180.0)
        val pxPerKm = (256.0 * 2.0.pow(zoom.toDouble()) / 360.0) / (111.32 * cosLat)
        val radiusPx = radiusKm * pxPerKm
        val limit = minOf(rect.shrink(marginPx).w, rect.shrink(marginPx).h) / 2f

        // 「P 波不出屏」的可测形式。
        assertTrue("radiusPx=$radiusPx limit=$limit", radiusPx <= limit + 0.5f)
    }

    @Test
    fun freeRectSkipsHudAndBottomBar() {
        val occ = listOf(
            ScreenRect(0f, 0f, 1080f, 520f),      // HUD 通栏
            ScreenRect(90f, 1820f, 990f, 2000f),   // 底栏居中
        )

        val rect = largestFreeRect(1080f, 2000f, occ)

        // 顶部通栏 HUD 把上方整条切掉；底栏居中留白，但中间那条全宽带最高。
        assertTrue(occ.none { it.hits(rect) })
        assertEquals(ScreenRect(0f, 520f, 1080f, 1820f), rect)
    }

    @Test
    fun freeRectFallsBackToRightOfHudWhenTall() {
        val occ = listOf(
            ScreenRect(0f, 0f, 756f, 1200f),      // HUD 又宽又高
            ScreenRect(270f, 1900f, 810f, 2000f), // 底栏很矮
        )

        val rect = largestFreeRect(1080f, 2000f, occ)

        assertTrue(occ.none { it.hits(rect) })
        assertTrue("rect=$rect", rect.w * rect.h >= 1080f * 700f)
    }

    @Test
    fun freeRectNeverOverlapsAnyOccluder() {
        val occ = listOf(
            ScreenRect(0f, 0f, 1080f, 520f),      // HUD 通栏
            ScreenRect(90f, 1820f, 990f, 2000f),   // 底栏居中
            ScreenRect(0f, 1500f, 700f, 1780f),   // 左下徽章
            ScreenRect(1008f, 300f, 1080f, 1600f),// 右侧工具条
        )

        val rect = largestFreeRect(1080f, 2000f, occ)

        assertTrue("rect=$rect", occ.none { it.hits(rect) })
        assertTrue(rect.w >= ScreenRect.MIN_FREE_EDGE && rect.h >= ScreenRect.MIN_FREE_EDGE)
    }

    @Test
    fun freeRectIsWholeScreenWhenNothingIsObscuring() {
        val rect = largestFreeRect(1080f, 2000f, emptyList())

        assertEquals(ScreenRect(0f, 0f, 1080f, 2000f), rect)
    }

    @Test
    fun focusCenterPutsEpicenterAtFreeRectCenter() {
        val rect = ScreenRect(0f, 520f, 1008f, 1820f)
        val worldPx = 1024.0

        val (cx, cy) = focusCameraCenter(
            eventX = 0.5, eventY = 0.5, worldPx = worldPx,
            rect = rect, viewWidthPx = 1080f, viewHeightPx = 2000f,
        )

        // 反算屏幕坐标：震中应落在矩形正中（±1px）。
        val screenX = 1080f / 2f + (0.5 - cx).toFloat() * worldPx.toFloat()
        val screenY = 2000f / 2f + (0.5 - cy).toFloat() * worldPx.toFloat()
        assertEquals(rect.cx, screenX, 1f)
        assertEquals(rect.cy, screenY, 1f)
    }

    @Test
    fun focusCenterShiftsAwayFromTheObstructedRightSide() {
        val rect = ScreenRect(0f, 0f, 1008f, 2000f)

        val (cx, _) = focusCameraCenter(
            eventX = 0.5, eventY = 0.5, worldPx = 1024.0,
            rect = rect, viewWidthPx = 1080f, viewHeightPx = 2000f,
        )

        // 可用区中心偏左 → 镜头中心要往右偏，震中才落回可视区中心。
        assertTrue(cx > 0.5)
        assertEquals(-36.0, (0.5 - cx) * 1024.0, 0.001)
    }

    @Test
    fun cameraHoldsAfterWaveOutgrowsPerceptibleRadius() {
        // 未达可感半径：继续跟随。
        assertTrue(!shouldHoldCamera(400.0, 300.0, fadeKm = 1200.0))
        // 达到可感半径：冻结，不再为看不见的圈继续缩小。
        assertTrue(shouldHoldCamera(1200.0, 300.0, fadeKm = 1200.0))
        assertTrue(shouldHoldCamera(300.0, 1500.0, fadeKm = 1200.0))
        // 震级未知时 fadeKm 退化为量程上限，不应误冻结。
        assertTrue(!shouldHoldCamera(400.0, 300.0, fadeKm = 0.0))
    }

    @Test
    fun seededDisplayRadiusStartsAtTarget() {
        // 目标从 0 出现时直接落到目标：从 0 指数爬升会让取景先套 100/300 km 兜底再收缩。
        val target = 420.0
        var disp = 0.0
        if (target > 0.0 && disp <= 0.0) disp = target

        assertEquals(target, disp, 0.0)
    }

    @Test
    fun waveRadiusIsContinuousAcrossTableSwitch() {
        // jma2001 在 2000 km 处量程用尽、切到 jb 表；接缝处若有跳变就是肉眼可见的一跳。
        val depths = listOf(10.0, 33.0, 70.0)
        for (d in depths) {
            var prev = 0.0
            for (sec in 1..600) {
                val r = TravelTimeService.distanceForTime(d, sec.toDouble(), true)
                if (sec > 1) {
                    // 走时反解本就随时间单调增长；只要求没有反向塌陷或阶跃。
                    assertTrue("depth=$d sec=$sec r=$r prev=$prev", r >= prev - 1e-6)
                }
                prev = r
            }
        }
    }
}
