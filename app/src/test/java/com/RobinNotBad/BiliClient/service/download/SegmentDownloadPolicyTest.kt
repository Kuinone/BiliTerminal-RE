package com.RobinNotBad.BiliClient.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片下载的看门狗与 Range 判定规则。
 * 这里覆盖的每一种情况，旧实现都会"默默卡住"或"按分片读整文件"，是 20% 进度不动的直接嫌疑。
 */
class SegmentDownloadPolicyTest {

    @Test
    fun 距最后一次字节超过预算才算停滞() {
        val last = 1_000_000L
        assertFalse(SegmentDownloadPolicy.hasStalled(last, last + SegmentDownloadPolicy.STALL_TIMEOUT_MS - 1))
        assertTrue(SegmentDownloadPolicy.hasStalled(last, last + SegmentDownloadPolicy.STALL_TIMEOUT_MS))
        assertTrue(SegmentDownloadPolicy.hasStalled(last, last + 60_000L))
    }

    @Test
    fun 看门狗预算必须大于建连超时() {
        // OkHttp connectTimeout 是 8s（NetWorkUtil），预算比它还短会误杀正常的慢建连
        assertTrue(SegmentDownloadPolicy.STALL_TIMEOUT_MS > 8_000L)
    }

    @Test
    fun 分片响应只认206() {
        assertTrue(SegmentDownloadPolicy.isPartialContent(206))
        assertFalse(SegmentDownloadPolicy.isPartialContent(200))
        assertFalse(SegmentDownloadPolicy.isPartialContent(302))
        assertFalse(SegmentDownloadPolicy.isPartialContent(403))
    }

    @Test
    fun 返回200说明服务端忽略了Range() {
        assertTrue(SegmentDownloadPolicy.isRangeIgnored(200))
        assertFalse(SegmentDownloadPolicy.isRangeIgnored(206))
        assertFalse(SegmentDownloadPolicy.isRangeIgnored(403))
    }

    @Test
    fun 能从ContentRange里取出分片起点() {
        assertEquals(0L, SegmentDownloadPolicy.parseContentRangeStart("bytes 0-1023/51973319"))
        assertEquals(10394663L, SegmentDownloadPolicy.parseContentRangeStart("bytes 10394663-20789325/51973319"))
        // 大小写不敏感，前导空格容错
        assertEquals(5L, SegmentDownloadPolicy.parseContentRangeStart(" Bytes 5-9/10 "))
        // 拿不到起点时返回 null，交给调用方不强判
        assertEquals(null, SegmentDownloadPolicy.parseContentRangeStart(null))
        assertEquals(null, SegmentDownloadPolicy.parseContentRangeStart(""))
        assertEquals(null, SegmentDownloadPolicy.parseContentRangeStart("bytes */51973319"))
        assertEquals(null, SegmentDownloadPolicy.parseContentRangeStart("max-age=3600"))
    }

    @Test
    fun 起点对不上就认定Range不可用() {
        assertTrue(SegmentDownloadPolicy.isContentRangeMismatch("bytes 0-1023/51973319", 10394663L))
        // 缺少 Content-Range 头时不强判（部分镜像不返回该头，但字节是准的）
        assertFalse(SegmentDownloadPolicy.isContentRangeMismatch(null, 10394663L))
        assertFalse(SegmentDownloadPolicy.isContentRangeMismatch("bytes 10394663-20789325/51973319", 10394663L))
    }

    @Test
    fun 只有结构性原因才在本次运行彻底禁用分片() {
        assertTrue(SegmentDownloadPolicy.shouldDisableSegmentMode(rangeUnsupported = true, stalled = false))
        assertTrue(SegmentDownloadPolicy.shouldDisableSegmentMode(rangeUnsupported = false, stalled = true))
        assertTrue(SegmentDownloadPolicy.shouldDisableSegmentMode(rangeUnsupported = true, stalled = true))
        // 单纯某一轮重试失败（网络抖一下）不该把分片永久关掉，下一片还能吃到多线程的速度
        assertFalse(SegmentDownloadPolicy.shouldDisableSegmentMode(rangeUnsupported = false, stalled = false))
    }

    @Test
    fun 取消分片后的等待预算必须短于正常收尾() {
        assertTrue(SegmentDownloadPolicy.ABORT_JOIN_TIMEOUT_MS < SegmentDownloadPolicy.FINISH_JOIN_TIMEOUT_MS)
        assertEquals(30_000L, SegmentDownloadPolicy.FINISH_JOIN_TIMEOUT_MS)
        // 取消场景要尽快把线程等死（等不到就放弃本次任务），不能拖到用户以为又卡住了
        assertTrue(SegmentDownloadPolicy.ABORT_JOIN_TIMEOUT_MS <= 5_000L)
    }
}
