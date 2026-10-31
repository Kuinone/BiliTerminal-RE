package com.RobinNotBad.BiliClient.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「倍速 ↔ 取流」判据的单测。
 *
 * 这些判据决定「倍速时要不要把 DASH 双流换成合一 MP4」以及「回到 1x 后要不要换回高清」：
 * 判反了不会崩，只会把用户锁在 720P，或者把「两路重叠、一路跟不上」的叠声 bug 带回来。
 * 真机/模拟器上的速率实测结论见 `docs/architecture-map.md` §7.57。
 */
class PlayerSpeedStreamPolicyTest {

    @Test
    fun `1倍速是原速`() {
        assertTrue(PlayerSpeedStreamPolicy.isNormalSpeed(1F))
        assertTrue(PlayerSpeedStreamPolicy.isNormalSpeed(1.0005F))
        assertTrue(PlayerSpeedStreamPolicy.isNormalSpeed(0.9995F))
    }

    @Test
    fun `所有非1倍速档位都算变速`() {
        // 滑条档位：0.5 / 0.75 / 1.25 / 1.5 / 1.75 / 2 / 3，长按加速还有 3x
        for (speed in floatArrayOf(0.5F, 0.75F, 1.25F, 1.5F, 1.75F, 2F, 3F)) {
            assertFalse("$speed 应视为变速", PlayerSpeedStreamPolicy.isNormalSpeed(speed))
        }
    }

    @Test
    fun `非1倍速都要求合一流`() {
        assertFalse(PlayerSpeedStreamPolicy.requiresCombinedStream(1F))
        assertTrue(PlayerSpeedStreamPolicy.requiresCombinedStream(2F))
        assertTrue(PlayerSpeedStreamPolicy.requiresCombinedStream(0.5F))
    }

    @Test
    fun `变速时高清档位被压到720P`() {
        assertEquals(64, PlayerSpeedStreamPolicy.targetQuality(80, 2F))    // 1080P → 720P
        assertEquals(64, PlayerSpeedStreamPolicy.targetQuality(120, 3F))   // 4K → 720P
        assertEquals(64, PlayerSpeedStreamPolicy.targetQuality(112, 1.5F)) // 1080P+ → 720P
    }

    @Test
    fun `变速时原本就更低的档位保持不动`() {
        assertEquals(32, PlayerSpeedStreamPolicy.targetQuality(32, 2F))   // 480P
        assertEquals(16, PlayerSpeedStreamPolicy.targetQuality(16, 0.5F)) // 360P
        assertEquals(64, PlayerSpeedStreamPolicy.targetQuality(64, 2F))   // 720P 本身就是合一上限
    }

    @Test
    fun `1倍速时请求用户选的档位`() {
        assertEquals(80, PlayerSpeedStreamPolicy.targetQuality(80, 1F))
        assertEquals(64, PlayerSpeedStreamPolicy.targetQuality(64, 1F))
    }

    @Test
    fun `倍速降档后回到1x要换回高清DASH`() {
        // 用户选 1080P、正在播合一 720P（没有外挂音轨）→ 回 1x 要恢复
        assertTrue(PlayerSpeedStreamPolicy.shouldRestoreDash(80, false, 1F))
        assertTrue(PlayerSpeedStreamPolicy.shouldRestoreDash(120, false, 1F))
    }

    @Test
    fun `本该走DASH时不做多余的换流`() {
        // 已经挂着外部音轨（说明正在播 DASH），1x 下无需再切
        assertFalse(PlayerSpeedStreamPolicy.shouldRestoreDash(80, true, 1F))
        // 720P 及以下本来就是合一 MP4，不必为它重建播放器
        assertFalse(PlayerSpeedStreamPolicy.shouldRestoreDash(64, false, 1F))
        assertFalse(PlayerSpeedStreamPolicy.shouldRestoreDash(32, false, 1F))
    }

    @Test
    fun `还在变速时不要提前恢复`() {
        assertFalse(PlayerSpeedStreamPolicy.shouldRestoreDash(80, false, 2F))
        assertFalse(PlayerSpeedStreamPolicy.shouldRestoreDash(80, false, 0.5F))
    }
}
