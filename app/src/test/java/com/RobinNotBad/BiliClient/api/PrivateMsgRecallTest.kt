package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.PrivateMessage
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 私信撤回（反馈 #57）：入口判据与「被撤回的原消息不再显示」。 */
class PrivateMsgRecallTest {

    private val self = 10086L
    private val now = 1_800_000_000L

    private fun msg(uid: Long = self, type: Int = PrivateMessage.TYPE_TEXT, sentAt: Long = now - 10, key: Long = 7345551441311046575L) =
        PrivateMessage(uid, type, JSONObject(), sentAt, "", key, 1L)

    @Test
    fun canRecall_自己两分钟内发的_可以() {
        assertTrue(PrivateMsgApi.canRecall(msg(), self, now))
        assertTrue(PrivateMsgApi.canRecall(msg(sentAt = now - 120), self, now))
    }

    @Test
    fun canRecall_超过两分钟_不行() {
        assertFalse(PrivateMsgApi.canRecall(msg(sentAt = now - 121), self, now))
    }

    @Test
    fun canRecall_本地时间比消息时间早_仍给入口交服务端判() {
        assertTrue(PrivateMsgApi.canRecall(msg(sentAt = now + 300), self, now))
    }

    @Test
    fun canRecall_别人发的_撤回通知_未登录_都不行() {
        assertFalse(PrivateMsgApi.canRecall(msg(uid = 2333L), self, now))
        assertFalse(PrivateMsgApi.canRecall(msg(type = PrivateMessage.TYPE_RETRACT), self, now))
        assertFalse(PrivateMsgApi.canRecall(msg(key = 0L), self, now))
        assertFalse(PrivateMsgApi.canRecall(msg(), 0L, now))
        assertFalse(PrivateMsgApi.canRecall(null, self, now))
    }

    @Test
    fun shouldDisplay_只隐藏被撤回的原消息() {
        assertTrue(PrivateMsgApi.shouldDisplay(PrivateMessage.STATUS_NORMAL))
        assertFalse(PrivateMsgApi.shouldDisplay(PrivateMessage.STATUS_RETRACTED))
        // 50 = 图片失效，内容是一张提示图，照常显示
        assertTrue(PrivateMsgApi.shouldDisplay(50))
    }
}
