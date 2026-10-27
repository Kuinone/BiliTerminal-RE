package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 举报评论的纯逻辑单测。
 *
 * 这块错了不会崩，只会「静默把举报提交到错误的分类」——用户看到「举报已提交」，
 * 而服务端收到的是另一个理由。所以码表、正文闸门、请求体三件事全部钉死。
 */
class ReportApiTest {

    // ---- 理由码表：bilibili-API/docs/comment/action.md「举报类型」 ----

    @Test
    fun reasonCodes_areZeroToEightInOrder() {
        // 码值是协议的一部分，不能重排；顺带确认没有漏项/多项
        assertEquals(9, ReportApi.commentReasonCodes().size)
        assertEquals((0..8).toList(), ReportApi.commentReasonCodes().toList())
    }

    @Test
    fun reasonLabels_matchCodesOneToOne() {
        // 少一条会 ArrayIndexOutOfBounds（弹窗直接崩），多一条则永远取不到
        assertEquals(ReportApi.commentReasonCodes().size, ReportApi.commentReasonLabels().size)
    }

    @Test
    fun reasonLabels_areTheOfficialOnes() {
        // 抽查三条：0 必须是「其他」（它决定要不要收正文），1/7 是客户端文案里最容易记反的两条
        assertEquals("其他", ReportApi.commentReasonLabel(0))
        assertEquals("垃圾广告", ReportApi.commentReasonLabel(1))
        assertEquals("人身攻击", ReportApi.commentReasonLabel(7))
    }

    @Test
    fun reasonLabel_unknownCodeKeepsTheNumber() {
        // 服务端加新理由时不能显示空白，否则用户在弹窗里点一行看不见的东西
        val label = ReportApi.commentReasonLabel(99)
        assertTrue("未知码也要能看出是第几号理由", label.contains("99"))
    }

    @Test
    fun reasonTables_areDefensiveCopies() {
        // 调用方拿到的是副本，改了也污染不到下一次弹窗
        val codes = ReportApi.commentReasonCodes()
        codes[0] = 42
        assertEquals(0, ReportApi.commentReasonCodes()[0])

        val labels = ReportApi.commentReasonLabels()
        labels[0] = "被改坏了"
        assertEquals("其他", ReportApi.commentReasonLabels()[0])
    }

    // ---- 正文闸门：只有「其他」要正文，且不少于 3 个字 ----

    @Test
    fun needsContent_onlyForOtherReason() {
        assertTrue("reason=0（其他）必须填正文", ReportApi.needsContent(ReportApi.REASON_OTHER))
        for (code in 1..8) {
            assertFalse("reason=$code 不该再收正文", ReportApi.needsContent(code))
        }
    }

    @Test
    fun isContentAcceptable_rejectsTooShortForOther() {
        // 客户端原文：举报理由不能少于3个字
        assertFalse("2 个字太少", ReportApi.isContentAcceptable(0, "广告"))
        assertFalse("空串不能提交", ReportApi.isContentAcceptable(0, ""))
        assertFalse("null 不能抛异常", ReportApi.isContentAcceptable(0, null))
        assertFalse("只有空白也不行", ReportApi.isContentAcceptable(0, "     "))
    }

    @Test
    fun isContentAcceptable_countsAfterTrim() {
        assertEquals(3, ReportApi.OTHER_CONTENT_MIN_LENGTH)
        assertTrue("正好 3 个字要放行", ReportApi.isContentAcceptable(0, "发广告"))
        assertTrue("前后空白不计入字数", ReportApi.isContentAcceptable(0, "  发广告  "))
        // 「发广告」两侧空白 4 个字符，若不 trim 会被误判成 7 个字
        assertFalse("空白撑起来的长度不算数", ReportApi.isContentAcceptable(0, "  ab  "))
    }

    @Test
    fun isContentAcceptable_rejectsOverlong() {
        val over = "字".repeat(ReportApi.OTHER_CONTENT_MAX_LENGTH + 1)
        assertFalse("超长要拦住（避免把整个评论区粘进去）", ReportApi.isContentAcceptable(0, over))
        val exact = "字".repeat(ReportApi.OTHER_CONTENT_MAX_LENGTH)
        assertTrue("正好到上限要放行", ReportApi.isContentAcceptable(0, exact))
    }

    @Test
    fun isContentAcceptable_ignoresContentForOtherReasons() {
        // 非「其他」理由不看正文，空串 / null 一律放行，别把没填正文当成错误
        assertTrue(ReportApi.isContentAcceptable(1, ""))
        assertTrue(ReportApi.isContentAcceptable(1, null))
        assertTrue(ReportApi.isContentAcceptable(1, "随手指纹"))
    }

    // ---- 请求体：参数名与「正文只在 reason=0 时带上」 ----

    /**
     * `FormData` 底层是 HashMap，toString 顺序不确定，所以按 `&` 拆开再比对；
     * 值会被 `URLEncoder.encode` 编码（中文变 %XX），这里解码回原文。
     */
    private fun paramsOf(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate {
            val at = it.indexOf('=')
            java.net.URLDecoder.decode(it.substring(0, at), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(at + 1), "UTF-8")
        }

    @Test
    fun buildReportForm_carriesProtocolFields() {
        val params = paramsOf(ReportApi.buildReportForm(170001L, 1, 99887766L, 4, "").toString())
        assertEquals("1", params["type"])
        assertEquals("170001", params["oid"])
        assertEquals("99887766", params["rpid"])
        assertEquals("4", params["reason"])
    }

    @Test
    fun buildReportForm_omitsContentForNonOtherReasons() {
        // 非「其他」也带 content 会让服务端多收一个无用字段；更糟的是可能被当成「其他」处理
        val params = paramsOf(ReportApi.buildReportForm(170001L, 1, 99887766L, 4, "这段不该出现").toString())
        assertFalse("reason=4 不应带 content", params.containsKey("content"))
    }

    @Test
    fun buildReportForm_trimsContentForOtherReason() {
        val params = paramsOf(ReportApi.buildReportForm(170001L, 1, 99887766L, 0, "  发广告  ").toString())
        assertEquals("正文要走 trim 后的值", "发广告", params["content"])
    }

    @Test
    fun buildReportForm_isPureAndHasNoCsrf() {
        // 纯函数不碰 SharedPreferences（单测里没有 Context）；csrf 由 reportReply 补，
        // 一旦有人把它挪回这里，JVM 单测会直接空指针
        val body = ReportApi.buildReportForm(1L, 1, 2L, 0, "abc").toString()
        assertFalse("csrf 不该出现在纯函数里", paramsOf(body).containsKey("csrf"))
        assertEquals("同样的入参必须给出同样的串", body, ReportApi.buildReportForm(1L, 1, 2L, 0, "abc").toString())
    }

    // ---- 错误码翻译：举报失败只回 code，不抛异常 ----

    @Test
    fun errorMsg_successIsSilent() {
        assertEquals("code==0 表示成功，不该再弹一条失败提示", "", ReportApi.errorMsg(0))
    }

    @Test
    fun errorMsg_distinguishesLoginProblems() {
        assertTrue("未登录要说清是没登录", ReportApi.errorMsg(-101).contains("登录"))
        assertTrue("凭证失效要说是失效", ReportApi.errorMsg(-111).contains("失效"))
        assertNotEquals("-101 与 -111 要给不同的动作指引", ReportApi.errorMsg(-101), ReportApi.errorMsg(-111))
    }

    @Test
    fun errorMsg_riskControlIsRecognisable() {
        // -352 / -412 是匿名或短时间连点最常见的两种拦法，提示必须能看明白是「被拦了」
        assertTrue(ReportApi.errorMsg(-352).isNotEmpty())
        assertTrue(ReportApi.errorMsg(-412).isNotEmpty())
    }

    @Test
    fun errorMsg_explainsCooldown() {
        // 12026 对应客户端文案「CD时间未到不能举报」，用户要知道是等一会儿而不是重试
        val msg = ReportApi.errorMsg(12026)
        assertTrue("冷却拒绝要提「稍后」", msg.contains("稍后"))
        assertFalse("冷却拒绝不能落到兜底文案", msg.startsWith("操作失败（错误码"))
    }

    @Test
    fun errorMsg_knownReportErrorsAreNotGeneric() {
        for (code in listOf(-101, -111, -352, -412, -400, -404, -509, 12002, 12006, 12026)) {
            val msg = ReportApi.errorMsg(code)
            assertTrue("code=$code 应有专属提示", msg.isNotEmpty())
            assertFalse("code=$code 不该落到兜底文案", msg.startsWith("操作失败（错误码"))
        }
    }

    @Test
    fun errorMsg_unknownCodeKeepsTheNumber() {
        // 遇到没见过的码，提示里必须带原始数字，否则用户/开发者没法定位
        assertTrue(ReportApi.errorMsg(99999).contains("99999"))
    }
}
