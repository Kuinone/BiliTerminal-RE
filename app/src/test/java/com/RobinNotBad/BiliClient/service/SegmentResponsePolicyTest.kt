package com.RobinNotBad.BiliClient.service

import com.RobinNotBad.BiliClient.service.download.SegmentResponsePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片（Range）响应校验的纯逻辑测试。
 *
 * 这里的判据错了不会崩，只会让多线程下载**静默产出错位的坏文件**：
 * 服务端若没按请求返回那一段（回整个文件、或区间起点不同），客户端仍会从
 * `seek(requestedStart)` 写下去，字节数还可能正好对得上，于是校验通过、文件却拼不起来。
 * 所以下面把「206 + 区间起点」这两道闸口的边界全部钉死。
 */
class SegmentResponsePolicyTest {

    private val mb = 1024 * 1024L

    // ---------- parseContentRange ----------

    @Test
    fun parseContentRange_标准格式() {
        val range = SegmentResponsePolicy.parseContentRange("bytes 0-2097151/12345678")
        assertEquals(0L, range!!.start)
        assertEquals(2097151L, range.end)
        assertEquals(12345678L, range.total)
    }

    @Test
    fun parseContentRange_总长为星号时给负一() {
        val range = SegmentResponsePolicy.parseContentRange("bytes 100-199/*")
        assertEquals(100L, range!!.start)
        assertEquals(199L, range.end)
        assertEquals("服务端不报总长时用 -1 表示未知", -1L, range.total)
    }

    @Test
    fun parseContentRange_大小写与多余空格都能认() {
        val range = SegmentResponsePolicy.parseContentRange("  BYTES 5-9/20  ")
        assertEquals(5L, range!!.start)
        assertEquals(9L, range.end)
        assertEquals(20L, range.total)
    }

    @Test
    fun parseContentRange_格式不认识一律null() {
        assertNull("空", SegmentResponsePolicy.parseContentRange(null))
        assertNull("空白", SegmentResponsePolicy.parseContentRange("   "))
        assertNull("缺 bytes 前缀", SegmentResponsePolicy.parseContentRange("0-100/200"))
        assertNull("缺总长", SegmentResponsePolicy.parseContentRange("bytes 0-100"))
        assertNull("缺区间", SegmentResponsePolicy.parseContentRange("bytes /200"))
        assertNull("区间缺结束", SegmentResponsePolicy.parseContentRange("bytes 0-/200"))
        assertNull("数字非法", SegmentResponsePolicy.parseContentRange("bytes a-b/200"))
        assertNull("倒序区间", SegmentResponsePolicy.parseContentRange("bytes 200-100/300"))
    }

    // ---------- isPartialResponseUsable ----------

    @Test
    fun partial_206且起点一致才可用() {
        assertTrue(
            SegmentResponsePolicy.isPartialResponseUsable(
                206, "bytes 2097152-4194303/6291456", 2097152L, 2 * mb
            )
        )
    }

    @Test
    fun partial_200一律不接受() {
        assertFalse(
            "服务端忽略 Range 回了整个文件：从这里 seek 下去会覆盖别的分片",
            SegmentResponsePolicy.isPartialResponseUsable(
                200, "bytes 0-6291455/6291456", 2097152L, 2 * mb
            )
        )
    }

    @Test
    fun partial_起点不对不接受() {
        assertFalse(
            "206 但回的是从头那一段，写进去就错位",
            SegmentResponsePolicy.isPartialResponseUsable(
                206, "bytes 0-2097151/6291456", 2097152L, 2 * mb
            )
        )
    }

    @Test
    fun partial_206缺ContentRange不接受() {
        assertFalse(
            "合法 206 必须带 Content-Range，缺失说明这个服务端行为不可信",
            SegmentResponsePolicy.isPartialResponseUsable(206, null, 0L, 2 * mb)
        )
    }

    @Test
    fun partial_区间比请求长不接受() {
        assertFalse(
            SegmentResponsePolicy.isPartialResponseUsable(
                206, "bytes 0-4194303/6291456", 0L, 2 * mb
            )
        )
    }

    @Test
    fun partial_最后一节短一点可以接受() {
        assertTrue(
            "末片吃掉整除余数，实际长度可能小于 segmentLen，只要不超过请求长度就允许",
            SegmentResponsePolicy.isPartialResponseUsable(
                206, "bytes 4194304-6291455/6291456", 4194304L, 2 * mb
            )
        )
    }

    // ---------- isSegmentComplete ----------

    @Test
    fun complete_恰好写满才算成功() {
        assertTrue(SegmentResponsePolicy.isSegmentComplete(2 * mb, 2 * mb))
        assertFalse("少写", SegmentResponsePolicy.isSegmentComplete(2 * mb - 1, 2 * mb))
        assertFalse("多写已经越界", SegmentResponsePolicy.isSegmentComplete(2 * mb + 1, 2 * mb))
        assertFalse("长度为 0 的分片不是合法分片", SegmentResponsePolicy.isSegmentComplete(0, 0))
        assertFalse(SegmentResponsePolicy.isSegmentComplete(0, 2 * mb))
    }
}
