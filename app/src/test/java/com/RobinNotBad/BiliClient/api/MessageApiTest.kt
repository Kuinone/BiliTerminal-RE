package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「收到的赞 → 弹幕」跳视频用的 BV 号提取（反馈 #44）。 */
class MessageApiTest {

    @Test
    fun parseBvid_plainVideoUri() {
        assertEquals("BV1xx411c7mD", MessageApi.parseBvid("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun parseBvid_ignoresQueryAndFragment() {
        assertEquals(
            "BV1xx411c7mD",
            MessageApi.parseBvid("https://www.bilibili.com/video/BV1xx411c7mD/?p=2&dm_progress=12#reply")
        )
    }

    @Test
    fun parseBvid_noBvid_returnsEmpty() {
        assertEquals("", MessageApi.parseBvid("https://www.bilibili.com/bangumi/play/ep12345"))
        assertEquals("", MessageApi.parseBvid(""))
        assertEquals("", MessageApi.parseBvid(null))
    }
}
