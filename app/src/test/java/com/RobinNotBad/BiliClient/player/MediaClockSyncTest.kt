package com.RobinNotBad.BiliClient.player

import master.flame.danmaku.controller.MediaClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaClockSync] 的纯 JVM 单测。
 *
 * 这个类决定弹幕时间轴怎么跟随播放器位置，改它等于改"弹幕与画面是否对得上"，
 * 所以每条规则都钉住：外推、限幅蠕动、不连续硬对齐。
 */
class MediaClockSyncTest {

    // ---- project：两次采样之间按倍速外推 ----

    @Test
    fun `同一时刻采样不外推`() {
        assertEquals(5_000L, MediaClockSync.project(5_000L, 1_000_000L, 1_000_000L, 1.0f))
    }

    @Test
    fun `一倍速按墙钟外推`() {
        // 采样后过了 250ms，1.0x 下理想位置就是 +250ms
        assertEquals(5_250L, MediaClockSync.project(5_000L, 1_000_000L, 1_000_250L, 1.0f))
    }

    @Test
    fun `倍速下推进更快`() {
        assertEquals(5_500L, MediaClockSync.project(5_000L, 1_000_000L, 1_000_250L, 2.0f))
        assertEquals(5_125L, MediaClockSync.project(5_000L, 1_000_000L, 1_000_250L, 0.5f))
    }

    @Test
    fun `空档超过一个采样周期就不再外推`() {
        // 采样停了（暂停/卡死）时不能任由外推无限跑，否则恢复后要追一大截
        val p = MediaClockSync.project(5_000L, 1_000_000L, 1_010_000L, 1.0f)
        assertEquals(6_000L, p)
    }

    @Test
    fun `采样时刻晚于当前时不倒推`() {
        assertEquals(5_000L, MediaClockSync.project(5_000L, 1_000_500L, 1_000_000L, 1.0f))
    }

    // ---- nextTimerValue：每帧限幅蠕动 ----

    @Test
    fun `误差在限幅内直接对齐`() {
        assertEquals(5_020L, MediaClockSync.nextTimerValue(5_000L, 5_020L))
        assertEquals(4_980L, MediaClockSync.nextTimerValue(5_000L, 4_980L))
    }

    @Test
    fun `误差超限幅只挪一步`() {
        assertEquals(
            5_000L + MediaClockSync.MAX_STEP_MS,
            MediaClockSync.nextTimerValue(5_000L, 9_000L)
        )
        assertEquals(
            5_000L - MediaClockSync.MAX_STEP_MS,
            MediaClockSync.nextTimerValue(5_000L, 1_000L)
        )
    }

    @Test
    fun `连续蠕动能在半秒内消化一次采样抖动`() {
        // 250ms 的采样跳变应当在一个采样周期内收敛，否则弹幕会持续落后
        var cur = 10_000L
        val target = 10_300L
        repeat(15) { cur = MediaClockSync.nextTimerValue(cur, target) }
        assertEquals(target, cur)
    }

    // ---- isDiscontinuity：跳转/换源要硬对齐 ----

    @Test
    fun `首次采样视为不连续`() {
        assertTrue(MediaClockSync.isDiscontinuity(-1L, 0L))
        assertTrue(MediaClockSync.isDiscontinuity(-1L, 123_456L))
    }

    @Test
    fun `正常推进不算不连续`() {
        // 250ms 采样间隔、2 倍速最多差 500ms，仍在阈值内
        assertFalse(MediaClockSync.isDiscontinuity(1_000L, 1_500L))
        assertFalse(MediaClockSync.isDiscontinuity(1_500L, 1_000L))
    }

    @Test
    fun `大跨度跳转算不连续`() {
        assertTrue(MediaClockSync.isDiscontinuity(1_000L, 600_000L))
        assertTrue("往回拖也要判定", MediaClockSync.isDiscontinuity(600_000L, 1_000L))
    }

    @Test
    fun `阈值本身不算不连续`() {
        assertFalse(MediaClockSync.isDiscontinuity(0L, MediaClockSync.DISCONTINUITY_MS))
    }
}
