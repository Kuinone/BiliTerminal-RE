package com.RobinNotBad.BiliClient.util

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整页表情预取的地址收集（26.10.27 性能）：收全、去重、只收会被渲染的位置。
 */
class EmoteUrlCollectorTest {

    @Test
    fun fromReplies_collectsRootAndChildEmotes_deduplicated() {
        val replies = JSONArray(
            """
            [
              {"content":{"message":"[doge][笑哭]","emote":{
                  "[doge]":{"url":"https://i0.hdslb.com/doge.png","meta":{"size":1}},
                  "[笑哭]":{"url":"https://i0.hdslb.com/xk.png","meta":{"size":1}}}},
               "replies":[
                  {"content":{"message":"[doge]","emote":{"[doge]":{"url":"https://i0.hdslb.com/doge.png","meta":{"size":1}}}}},
                  {"content":{"message":"[吃瓜]","emote":{"[吃瓜]":{"url":"https://i0.hdslb.com/cg.png","meta":{"size":1}}}}}
               ]},
              {"content":{"message":"无表情"}},
              {"content":{"message":"x","emote":null},"replies":null}
            ]
            """
        )
        assertEquals(
            listOf("https://i0.hdslb.com/doge.png", "https://i0.hdslb.com/xk.png", "https://i0.hdslb.com/cg.png"),
            EmoteUrlCollector.fromReplies(replies).toList()
        )
    }

    @Test
    fun fromReplies_nullOrEmpty_returnsEmpty() {
        assertTrue(EmoteUrlCollector.fromReplies(null).isEmpty())
        assertTrue(EmoteUrlCollector.fromReplies(JSONArray()).isEmpty())
    }

    @Test
    fun fromDynamicItems_collectsDescAndForwardedOrig() {
        val items = JSONArray(
            """
            [
              {"modules":{"module_dynamic":{"desc":{"rich_text_nodes":[
                  {"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"hi"},
                  {"type":"RICH_TEXT_NODE_TYPE_EMOJI","text":"[doge]","emoji":{"icon_url":"https://i0.hdslb.com/doge.png","size":1}}
               ]}}},
               "orig":{"modules":{"module_dynamic":{"major":{"opus":{"summary":{"rich_text_nodes":[
                  {"type":"RICH_TEXT_NODE_TYPE_EMOJI","text":"[吃瓜]","emoji":{"icon_url":"https://i0.hdslb.com/cg.png","size":1}}
               ]}}}}}}},
              {"modules":{"module_dynamic":{"desc":null}}}
            ]
            """
        )
        assertEquals(
            listOf("https://i0.hdslb.com/doge.png", "https://i0.hdslb.com/cg.png"),
            EmoteUrlCollector.fromDynamicItems(items).toList()
        )
    }

    @Test
    fun fromDynamicItems_ignoresEmojiOutsideModuleDynamic() {
        // 评论预览等位置的表情不在卡片上渲染，不该被预取
        val items = JSONArray(
            """
            [{"modules":{"module_interaction":{"items":[{"desc":{"rich_text_nodes":[
                {"type":"RICH_TEXT_NODE_TYPE_EMOJI","emoji":{"icon_url":"https://i0.hdslb.com/x.png"}}
            ]}}]}}}]
            """
        )
        assertTrue(EmoteUrlCollector.fromDynamicItems(items).isEmpty())
    }
}
