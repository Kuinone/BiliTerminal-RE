package com.RobinNotBad.BiliClient.player

import com.RobinNotBad.BiliClient.player.ViewPointSkip.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkipCancelPolicy] 的纯 JVM 单测（26.10.22「乱跳」修复）。
 *
 * 钉住两件**不会报错、只会乱跳**的事：
 * 1. **自动跳过自己不能算「用户反悔」**——原实现里 `seekToVideo()` 分不清调用者，
 *    自动跳过与用户操作撞在同一帧时会把片段误标成已处理，真正该跳的片段被吃掉。
 * 2. **重复拉取同一批片段时不能清空已处理集合**——`loadViewPoints()` 一次播放里会被调用多次，
 *    原先无条件 `skipHandled.clear()`，响应晚到时「片头已处理」被抹掉，于是再跳一次。
 */
class SkipCancelPolicyTest {

    private val op = Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)
    private val ed = Segment(ViewPointSkip.TYPE_ED, 1380.0, 1440.0)

    // ---------- cancelKeyFor：谁进来算「反悔」 ----------

    @Test
    fun 用户主动跳进片段算反悔() {
        val segments = ViewPointSkip.buildSegments(listOf(op))
        val key = ViewPointSkip.keyForManualSeek(30.0, segments, emptySet())
        assertEquals(
            "用户主动拖进片头，应把该片段记为已处理，否则下一轮定时器又把他弹走",
            ViewPointSkip.keyOf(op),
            SkipCancelPolicy.cancelKeyFor(true, key, emptySet())
        )
    }

    @Test
    fun 自动跳过自己不算用户反悔() {
        // 这是本次修复的核心：自动跳过也走 seekToVideo，但它不能被当成「用户想留在这段里」
        assertEquals(
            "自动跳过调用 seekToVideo 时必须标记为「非用户发起」",
            null,
            SkipCancelPolicy.cancelKeyFor(false, ViewPointSkip.keyOf(op), emptySet())
        )
    }

    @Test
    fun 落点不在任何片段内不需要标记() {
        assertNull(SkipCancelPolicy.cancelKeyFor(true, null, emptySet()))
    }

    @Test
    fun 已处理过的片段不必重复标记() {
        val handled = setOf(ViewPointSkip.keyOf(op))
        assertNull(
            SkipCancelPolicy.cancelKeyFor(true, ViewPointSkip.keyOf(op), handled)
        )
    }

    // ---------- segmentsChanged：什么时候允许清空已处理集合 ----------

    @Test
    fun 首次加载视为内容变化() {
        assertTrue(
            "第一次拿到片段（原为空表）必须能写入并开始判定",
            SkipCancelPolicy.segmentsChanged(emptyList(), ViewPointSkip.buildSegments(listOf(op)))
        )
    }

    @Test
    fun 重复拉到完全相同的片段不算变化() {
        val first = ViewPointSkip.buildSegments(listOf(op, ed))
        val again = ViewPointSkip.buildSegments(listOf(op, ed))
        assertFalse(
            "同一次播放里重复拉取（迟到的响应、投屏恢复）不能重置，否则已跳过的片头会被重新跳一次",
            SkipCancelPolicy.segmentsChanged(first, again)
        )
    }

    @Test
    fun 片段内容变了视为内容变化() {
        val first = ViewPointSkip.buildSegments(listOf(op))
        val next = ViewPointSkip.buildSegments(listOf(op, ed))
        assertTrue("多出片尾说明确实换内容了，应当重置已处理集合", SkipCancelPolicy.segmentsChanged(first, next))
    }

    @Test
    fun 片段数量相同但区间不同也算变化() {
        val first = ViewPointSkip.buildSegments(listOf(Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)))
        val next = ViewPointSkip.buildSegments(listOf(Segment(ViewPointSkip.TYPE_OP, 0.0, 60.0)))
        assertTrue(SkipCancelPolicy.segmentsChanged(first, next))
    }

    @Test
    fun 换成不同集的片段也算变化() {
        val ep1 = ViewPointSkip.buildSegments(listOf(Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)))
        val ep2 = ViewPointSkip.buildSegments(listOf(Segment(ViewPointSkip.TYPE_OP, 5.0, 100.0)))
        assertTrue("换集后必须重置，否则上一集的「已处理」会挡住这一集", SkipCancelPolicy.segmentsChanged(ep1, ep2))
    }

    @Test
    fun 两批都为空不算变化() {
        assertFalse(
            "没有片头片尾的视频反复加载，不该产生任何重置动作",
            SkipCancelPolicy.segmentsChanged(emptyList(), emptyList())
        )
    }

    @Test
    fun 有内容变成空算变化() {
        // 上游这次没返回 view_points（失败/风控）：片段清空，已处理集合跟着清也无所谓
        assertTrue(
            SkipCancelPolicy.segmentsChanged(ViewPointSkip.buildSegments(listOf(op)), emptyList())
        )
    }
}
