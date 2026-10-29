package com.RobinNotBad.BiliClient.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「收到的赞 / 回复我的」里弹幕类条目的解析（反馈 #44）。
 *
 * 两条链路都靠**同一份口径**取弹幕原文与所在视频，所以抽成纯函数钉死：
 * 之前「回复我的」没有 danmu 分支，只显示「无法识别这个类别：danmu」；
 * 而 `business_id` / `source_content` 用 `getInt`/`getString` 读，弹幕条目缺字段时
 * 直接抛 JSONException，把整页消息带崩（不是少一条，是整页空白）。
 */
class MessageApiDanmuTest {

    // ---- business_id：缺字段不能抛 ----

    @Test
    fun parseBusinessId_readsValueWhenPresent() {
        assertEquals(1, MessageApi.parseBusinessId(JSONObject("""{"business_id":1}""")))
        assertEquals(11, MessageApi.parseBusinessId(JSONObject("""{"business_id":11}""")))
    }

    @Test
    fun parseBusinessId_missingFieldFallsBackToMinusOne() {
        // 弹幕类条目不带这个字段（官方 Web 消息中心：type === "danmu" ? 0 : business_id）。
        // 旧代码是 getInt，这里会抛 JSONException。
        assertEquals(-1, MessageApi.parseBusinessId(JSONObject("""{"type":"danmu"}""")))
    }

    @Test
    fun parseBusinessId_nullIsTolerated() {
        assertEquals(-1, MessageApi.parseBusinessId(null))
    }

    // ---- 弹幕原文：两个流放的位置不一样 ----

    @Test
    fun parseDanmuText_likeStreamUsesTitle() {
        // 收到的赞：title 就是弹幕原文
        val item = JSONObject("""{"type":"danmu","title":"这波操作可以"}""")
        assertEquals("这波操作可以", MessageApi.parseDanmuText(item))
    }

    @Test
    fun parseDanmuText_replyStreamPrefersSourceContent() {
        // 回复我的：source_content 是被回复的内容（我的弹幕原文），title 变成了视频标题
        val item = JSONObject(
            """{"type":"danmu","source_content":"这波操作可以","title":"【某视频标题】"}"""
        )
        assertEquals("这波操作可以", MessageApi.parseDanmuText(item))
    }

    @Test
    fun parseDanmuText_fallsBackToTitleWhenSourceContentEmpty() {
        val item = JSONObject("""{"type":"danmu","source_content":"","title":"兜底标题"}""")
        assertEquals("兜底标题", MessageApi.parseDanmuText(item))
    }

    @Test
    fun parseDanmuText_missingEverythingReturnsEmpty() {
        assertEquals("", MessageApi.parseDanmuText(JSONObject("""{"type":"danmu"}""")))
        assertEquals("", MessageApi.parseDanmuText(null))
    }

    // ---- 所在视频：只给 uri，要自己抠 BV ----

    @Test
    fun parseDanmuBvid_extractsFromUri() {
        val item = JSONObject(
            """{"type":"danmu","uri":"https://www.bilibili.com/video/BV1xx411c7mD?p=2#reply"}"""
        )
        assertEquals("BV1xx411c7mD", MessageApi.parseDanmuBvid(item))
    }

    @Test
    fun parseDanmuBvid_missingUriReturnsEmpty() {
        // 取不到 BV 时 UI 会提示「没找到这条弹幕所在的视频」，不能抛
        assertEquals("", MessageApi.parseDanmuBvid(JSONObject("""{"type":"danmu"}""")))
        assertEquals("", MessageApi.parseDanmuBvid(null))
        assertEquals(
            "",
            MessageApi.parseDanmuBvid(JSONObject("""{"uri":"https://www.bilibili.com/bangumi/play/ep1"}"""))
        )
    }
}
