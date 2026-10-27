package com.RobinNotBad.BiliClient.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从一整页接口响应里收集要用到的表情图地址，交给 [EmoteUtil.prefetch] 并发预取（26.10.27 性能）。
 *
 * 纯函数、只依赖 org.json，便于 JVM 单测。只收集**会被渲染**的位置，免得替用不到的图白下载：
 * - 评论：`content.emote` 的每个值的 `url`，以及楼中楼 `replies` 里的同一结构；
 * - 动态：`modules.module_dynamic` 子树里 `RICH_TEXT_NODE_TYPE_EMOJI` 节点的 `emoji.icon_url`，
 *   转发动态再递归 `orig`。
 */
object EmoteUrlCollector {

    @JvmStatic
    fun fromReplies(replies: JSONArray?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        collectReplies(replies, out)
        return out
    }

    private fun collectReplies(replies: JSONArray?, out: MutableSet<String>) {
        if (replies == null) return
        for (i in 0 until replies.length()) {
            val reply = replies.optJSONObject(i) ?: continue
            val emote = reply.optJSONObject("content")?.optJSONObject("emote")
            if (emote != null) {
                val keys = emote.keys()
                while (keys.hasNext()) {
                    val url = emote.optJSONObject(keys.next())?.optString("url").orEmpty()
                    if (url.isNotEmpty()) out.add(url)
                }
            }
            collectReplies(reply.optJSONArray("replies"), out)
        }
    }

    @JvmStatic
    fun fromDynamicItems(items: JSONArray?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (items == null) return out
        for (i in 0 until items.length()) collectDynamic(items.optJSONObject(i), out)
        return out
    }

    private fun collectDynamic(item: JSONObject?, out: MutableSet<String>) {
        if (item == null) return
        collectEmojiNodes(item.optJSONObject("modules")?.opt("module_dynamic"), out)
        collectDynamic(item.optJSONObject("orig"), out)
    }

    /** 在任意 JSON 子树里找表情富文本节点。 */
    private fun collectEmojiNodes(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                if (node.optString("type") == "RICH_TEXT_NODE_TYPE_EMOJI") {
                    val url = node.optJSONObject("emoji")?.optString("icon_url").orEmpty()
                    if (url.isNotEmpty()) out.add(url)
                }
                val keys = node.keys()
                while (keys.hasNext()) collectEmojiNodes(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectEmojiNodes(node.opt(i), out)
        }
    }
}
