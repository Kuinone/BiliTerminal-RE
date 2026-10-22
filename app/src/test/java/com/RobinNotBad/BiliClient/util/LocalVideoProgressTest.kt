package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住 [LocalVideoProgress] 的边界：续播位置的"读"与"写"必须用同一套阈值，
 * 否则会出现"存了但读不出来"（比如存 5 秒、读的时候又按别的阈值挡掉）。
 *
 * 该对象刻意不碰 Context / 文件，所以能在纯 JVM 单测里跑（本仓库不用 Robolectric）。
 */
class LocalVideoProgressTest {

    // ==================== resumeTargetMs：进播放器时算该跳到哪 ====================

    @Test
    fun `没存过进度就从头播`() {
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(0L, 600_000L))
    }

    @Test
    fun `位置不足 5 秒不续播`() {
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(3_000L, 600_000L))
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(5_000L, 600_000L))
        assertEquals(5_001L, LocalVideoProgress.resumeTargetMs(5_001L, 600_000L))
    }

    @Test
    fun `正常位置原样返回`() {
        assertEquals(90_000L, LocalVideoProgress.resumeTargetMs(90_000L, 600_000L))
    }

    @Test
    fun `距结尾不足 5 秒算看完`() {
        // 时长 10 分钟，存了 9:56（距结尾 4 秒）→ 从头
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(596_000L, 600_000L))
        // 恰好 5 秒也按看完处理（<= 容差）
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(595_000L, 600_000L))
        assertTrueResume(594_999L, 600_000L)
    }

    @Test
    fun `存的位置超过总时长按看完处理`() {
        // 视频被换成更短的版本（或时长读小了）：seek 到末尾只会立刻触发播放完成，不如从头播
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(700_000L, 600_000L))
    }

    @Test
    fun `时长未知时只按位置判断`() {
        assertEquals(90_000L, LocalVideoProgress.resumeTargetMs(90_000L, 0L))
        assertEquals(0L, LocalVideoProgress.resumeTargetMs(1_000L, 0L))
    }

    // ==================== progressToSave：退出/周期落盘时算该存什么 ====================

    @Test
    fun `位置 0 或负数不存`() {
        assertEquals(0L, LocalVideoProgress.progressToSave(0L, 600_000L))
        assertEquals(0L, LocalVideoProgress.progressToSave(-1L, 600_000L))
    }

    @Test
    fun `正常位置原样存`() {
        assertEquals(90_000L, LocalVideoProgress.progressToSave(90_000L, 600_000L))
    }

    @Test
    fun `看到结尾就清零`() {
        assertEquals(0L, LocalVideoProgress.progressToSave(596_000L, 600_000L))
        assertEquals(0L, LocalVideoProgress.progressToSave(600_000L, 600_000L))
    }

    @Test
    fun `超过总时长同样清零`() {
        assertEquals(0L, LocalVideoProgress.progressToSave(700_000L, 600_000L))
    }

    @Test
    fun `时长未知时位置原样存`() {
        assertEquals(90_000L, LocalVideoProgress.progressToSave(90_000L, 0L))
    }

    // ==================== 读写的阈值必须一致 ====================

    @Test
    fun `写进去的位置一定能读出来`() {
        // 这条是防回归的核心：同一个位置，progressToSave 存下来的值再过 resumeTargetMs
        // 必须原样读回（否则表现是"退出后从上次位置播放"时灵时不灵）
        val durationMs = 600_000L
        for (positionMs in longArrayOf(5_001L, 30_000L, 90_000L, 300_000L, 594_999L)) {
            val saved = LocalVideoProgress.progressToSave(positionMs, durationMs)
            assertEquals(positionMs, LocalVideoProgress.resumeTargetMs(saved, durationMs))
        }
    }

    private fun assertTrueResume(savedMs: Long, durationMs: Long) {
        assertEquals(savedMs, LocalVideoProgress.resumeTargetMs(savedMs, durationMs))
    }
}
