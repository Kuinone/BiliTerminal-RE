package com.RobinNotBad.BiliClient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Reply] 翻译状态三个纯函数（[Reply.hasTranslation] / [Reply.toggleTranslationShown] /
 * [Reply.applyTranslation]）的 JVM 单测。
 *
 * 钉死三件容易写错的事：
 *  1. 「有译文」= 非 null **且非空**——翻译接口对不可译评论会回空串，空串若算有译文，
 *     界面会显示一块空白，且按钮点了没有任何视觉变化；
 *  2. 没有译文时切换必须失败且不改状态，否则会提前把 translationShown 打开；
 *  3. 空译文失败时不能覆盖已有译文 / 显示状态；成功时必须清掉 translationLoading，
 *     否则按钮会永久卡在禁用态。
 */
class ReplyTranslationTest {

    @Test
    fun newReply_hasNoTranslationAndStaysHidden() {
        val reply = Reply()

        assertFalse("新建的评论还没翻译过，hasTranslation() 必须是 false", reply.hasTranslation())
        assertFalse("译文默认不显示（否则每条评论下面都会空出一块）", reply.translationShown)
        assertFalse("译文请求默认不在途", reply.translationLoading)
        assertNull("没翻译过时 translatedMessage 必须是 null，不能用空串冒充「有译文」", reply.translatedMessage)
    }

    @Test
    fun emptyTranslatedMessage_isNotTreatedAsTranslation() {
        val reply = Reply()
        reply.translatedMessage = ""

        assertFalse("空串不算有译文（否则界面会显示一行空白，点按钮也毫无反馈）", reply.hasTranslation())
        assertFalse("空译文时切换必须被拒绝", reply.toggleTranslationShown())
        assertFalse("拒绝切换后不能让显示状态意外打开", reply.translationShown)
    }

    @Test
    fun toggleWithoutTranslation_returnsFalseAndKeepsState() {
        val reply = Reply()

        assertFalse("没有译文时 toggleTranslationShown() 必须返回 false", reply.toggleTranslationShown())
        assertFalse("没有译文时不能把 translationShown 打开", reply.translationShown)
        assertNull("切换失败不能凭空造出译文", reply.translatedMessage)
    }

    @Test
    fun applyTranslation_setsTextAndShowsIt() {
        val reply = Reply()

        val applied = reply.applyTranslation("你好")

        assertTrue("拿到的译文非空时必须应用成功", applied)
        assertEquals("译文要原样存下来", "你好", reply.translatedMessage)
        assertTrue("新译文一拿到就应显示在原文下方", reply.translationShown)
        assertTrue("应用后 hasTranslation() 必须为 true", reply.hasTranslation())
    }

    @Test
    fun toggleTwice_returnsToOriginalVisibility() {
        val reply = Reply()
        reply.applyTranslation("Hello")

        assertFalse("第一次切换应把显示中的译文收起", reply.toggleTranslationShown())
        assertFalse("收起后 translationShown 必须是 false", reply.translationShown)
        assertTrue("第二次切换应重新显示", reply.toggleTranslationShown())
        assertTrue("再切换回来必须恢复显示", reply.translationShown)
        assertEquals("切换只改显示状态，不能丢译文", "Hello", reply.translatedMessage)
    }

    @Test
    fun emptyOrNullTranslation_dropsWithoutTouchingExistingState() {
        val reply = Reply()
        reply.applyTranslation("原文译文")
        reply.toggleTranslationShown() // 先收起来，验证失败的空译文不会把显示状态改回来
        assertFalse("前置条件：译文应处于收起状态", reply.translationShown)

        assertFalse("null 译文必须返回 false", reply.applyTranslation(null))
        assertFalse("空串译文必须返回 false", reply.applyTranslation(""))

        assertEquals("空译文不能覆盖已有译文", "原文译文", reply.translatedMessage)
        assertFalse("空译文不能把已收起的译文重新打开", reply.translationShown)
    }

    @Test
    fun applyTranslation_clearsLoadingFlag() {
        val reply = Reply()
        reply.translationLoading = true

        reply.applyTranslation("译文")

        assertFalse("请求成功后必须清掉 translationLoading，否则「文A」按钮会永久禁用", reply.translationLoading)
    }
}
