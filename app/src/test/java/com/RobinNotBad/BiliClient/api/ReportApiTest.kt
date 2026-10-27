package com.RobinNotBad.BiliClient.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 举报评论的纯逻辑单测。
 *
 * 这块错了不会崩，只会「静默把举报提交成错误的分类」——用户看到「举报已提交」，
 * 服务端收到的却是另一个理由。所以理由表、正文闸门、请求体、错误码四件事全部钉死。
 */
class ReportApiTest {

    @After
    fun tearDown() {
        // fetchReasons 会写进程级缓存，别让用例之间互相污染
        ReportApi.clearReasonCache()
    }

    private fun reasonOf(code: Int) =
        ReportApi.defaultReasons().first { it.code == code }

    // ---- 静态兜底表：逐项抄自官方 H5 举报页（comment-h5 chunk 919） ----

    @Test
    fun defaultReasons_usesTheCodesTheServerActuallyUses() {
        // 官方现行不是 0..17 连续编号（那是旧快照的错）。这 18 个码是 H5 兜底表原文。
        val expected = listOf(9, 2, 10, 12, 23, 19, 22, 20, 7, 15, 1, 4, 5, 3, 8, 18, 17, 0)
        assertEquals(expected, ReportApi.defaultReasons().map { it.code })
    }

    @Test
    fun defaultReasons_dropsCodesThatNoLongerExist() {
        // 6（政治）、11、13、14、16、21 在官方现行表里都没有。
        // 6 尤其要注意：第一版我们发过 6，官方已经改用 19（涉政谣言）。
        val codes = ReportApi.defaultReasons().map { it.code }.toSet()
        for (gone in listOf(6, 11, 13, 14, 16, 21)) {
            assertFalse("$gone 已不在官方理由表里", gone in codes)
        }
    }

    @Test
    fun defaultReasons_keepsOfficialLabels() {
        // 抽查几条最容易照旧快照写错的
        assertEquals("违法违规", reasonOf(9).label)
        assertEquals("视频不相关", reasonOf(8).label)   // 不是「内容不相关」
        assertEquals("违法信息外链", reasonOf(23).label)
        assertEquals("涉社会事件谣言", reasonOf(20).label)
        assertEquals("违规抽奖", reasonOf(18).label)
        assertEquals("其他", reasonOf(0).label)
    }

    @Test
    fun defaultReasons_onlyOtherNeedsContent() {
        val need = ReportApi.defaultReasons().filter { it.contentRequired }.map { it.code }
        assertEquals(listOf(ReportApi.REASON_OTHER), need)
    }

    @Test
    fun defaultReasons_isDefensiveCopy() {
        val list = ReportApi.defaultReasons()
        assertFalse("返回的应是不可变副本", runCatching { (list as MutableList<ReportApi.Reason>).clear() }.isSuccess)
        assertEquals(18, ReportApi.defaultReasons().size)
    }

    @Test
    fun cachedOrDefaultReasons_fallsBackToStaticTable() {
        ReportApi.clearReasonCache()
        assertNull(ReportApi.cachedReasons())
        assertEquals(ReportApi.defaultReasons().size, ReportApi.cachedOrDefaultReasons().size)
    }

    // ---- metadata 解析（服务端下发） ----

    @Test
    fun parseReasons_readsFlatReasonList() {
        val json = """
            {"code":0,"data":{"reason_list":[
              {"tag_id":9,"tag_name":"违法违规","content_required":0},
              {"tag_id":0,"tag_name":"其他","content_required":1}
            ],"can_delete":0}}
        """.trimIndent()
        val list = ReportApi.parseReasons(json)!!
        assertEquals(2, list.size)
        assertEquals(9, list[0].code)
        assertEquals("违法违规", list[0].label)
        assertFalse(list[0].contentRequired)
        assertTrue("content_required=1 要认成必填", list[1].contentRequired)
    }

    @Test
    fun parseReasons_readsGroupedReportGroups() {
        val json = """
            {"code":0,"data":{"report_groups":[
              {"name":"违反法律法规","report_options":[{"value":9,"label":"违法违规"},{"value":2,"label":"色情"}]},
              {"name":"有害社区环境","report_options":[{"value":1,"label":"垃圾广告"}]}
            ]}}
        """.trimIndent()
        val list = ReportApi.parseReasons(json)!!
        assertEquals(listOf(9, 2, 1), list.map { it.code })
        assertEquals("垃圾广告", list[2].label)
    }

    @Test
    fun parseReasons_acceptsStringTruthyForContentRequired() {
        // content_required 在服务端可能是 1/0，也可能是 "1"/"true"
        val json = """{"code":0,"data":{"reason_list":[{"tag_id":0,"tag_name":"其他","content_required":"1"}]}}"""
        assertTrue(ReportApi.parseReasons(json)!![0].contentRequired)
    }

    @Test
    fun parseReasons_returnsNullWhenUnusable() {
        // null 表示「本次用不了」，调用方回落静态表——不能返回空列表，那样弹窗会是空的
        assertNull(ReportApi.parseReasons(null))
        assertNull("code != 0 要当不可用", ReportApi.parseReasons("""{"code":-101,"message":"账号未登录"}"""))
        assertNull(ReportApi.parseReasons("""{"code":0,"data":{}}"""))
        assertNull(ReportApi.parseReasons("""{"code":0,"data":{"reason_list":[]}}"""))
        assertNull(
            "缺 tag_id/tag_name 的项要跳过，全跳过就当不可用",
            ReportApi.parseReasons("""{"code":0,"data":{"reason_list":[{"tag_id":9}]}}""")
        )
    }

    @Test
    fun parseReasons_skipsBrokenItemsButKeepsGoodOnes() {
        val json = """
            {"code":0,"data":{"reason_list":[
              {"tag_id":9,"tag_name":"违法违规"},
              {"tag_name":"没有码"},
              {"tag_id":2,"tag_name":""}
            ]}}
        """.trimIndent()
        val list = ReportApi.parseReasons(json)!!
        assertEquals(1, list.size)
        assertEquals(9, list[0].code)
    }

    // ---- 正文闸门：需正文的理由 2~200 字符 ----

    @Test
    fun contentBounds_matchTheOfficialH5() {
        // H5：`if (otherContent && otherContent.length < 2)` + 「自定义举报内容长度为2-200字符」
        assertEquals(2, ReportApi.CONTENT_MIN_LENGTH)
        assertEquals(200, ReportApi.CONTENT_MAX_LENGTH)
    }

    @Test
    fun isContentAcceptable_rejectsTooShortForOther() {
        val other = reasonOf(0)
        assertFalse("1 个字太短", ReportApi.isContentAcceptable(other, "坏"))
        assertFalse("空串不能提交", ReportApi.isContentAcceptable(other, ""))
        assertFalse("null 不能抛异常", ReportApi.isContentAcceptable(other, null))
        assertFalse("只有空白也不行", ReportApi.isContentAcceptable(other, "   "))
        assertTrue("正好 2 个字要放行", ReportApi.isContentAcceptable(other, "广告"))
        assertTrue("前后空白不计入字数", ReportApi.isContentAcceptable(other, "  广告  "))
    }

    @Test
    fun isContentAcceptable_rejectsOverlong() {
        val other = reasonOf(0)
        val exact = "字".repeat(ReportApi.CONTENT_MAX_LENGTH)
        assertTrue("正好 200 字放行", ReportApi.isContentAcceptable(other, exact))
        assertFalse("201 字要拦住", ReportApi.isContentAcceptable(other, exact + "字"))
    }

    @Test
    fun isContentAcceptable_ignoresContentForOtherReasons() {
        // 不要正文的理由不该因为空正文被拦住
        val spam = reasonOf(1)
        assertTrue(ReportApi.isContentAcceptable(spam, ""))
        assertTrue(ReportApi.isContentAcceptable(spam, null))
        assertTrue(ReportApi.isContentAcceptable(null, null))
    }

    @Test
    fun needsContent_followsServerFlag() {
        // 服务端可以把任意一项标成必填，UI 不能只认「其他」
        val forced = ReportApi.Reason(9, "违法违规", true)
        assertTrue(ReportApi.needsContent(forced))
        assertFalse(ReportApi.needsContent(reasonOf(9)))
    }

    // ---- 请求体：参数名与「正文只在需要时带上」 ----

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
        val params = paramsOf(
            ReportApi.buildReportForm(170001L, 1, 99887766L, reasonOf(4), "").toString()
        )
        assertEquals("1", params["type"])
        assertEquals("170001", params["oid"])
        assertEquals("99887766", params["rpid"])
        assertEquals("4", params["reason"])
    }

    @Test
    fun buildReportForm_omitsContentForNonContentReasons() {
        // 非必填理由也带 content 会让服务端多收一个无用字段
        val params = paramsOf(
            ReportApi.buildReportForm(170001L, 1, 99887766L, reasonOf(4), "这段不该出现").toString()
        )
        assertFalse("引战不需要正文，不该带 content", params.containsKey("content"))
    }

    @Test
    fun buildReportForm_trimsContentWhenRequired() {
        val params = paramsOf(
            ReportApi.buildReportForm(170001L, 1, 99887766L, reasonOf(0), "  发广告  ").toString()
        )
        assertEquals("正文要走 trim 后的值", "发广告", params["content"])
    }

    @Test
    fun buildReportForm_sendsNewCodesNotJustZeroToEight() {
        // 9/10/12/23 这些码第一版根本不存在，直接钉住
        for (code in listOf(9, 10, 12, 15, 17, 18, 19, 20, 22, 23)) {
            val params = paramsOf(
                ReportApi.buildReportForm(1L, 1, 2L, reasonOf(code), "").toString()
            )
            assertEquals("reason 应等于 $code", code.toString(), params["reason"])
        }
    }

    @Test
    fun buildReportForm_isPureAndHasNoCsrf() {
        // 纯函数不碰 SharedPreferences（单测里没有 Context）；csrf 由 reportReply 补，
        // 一旦有人把它挪回这里，JVM 单测会直接空指针
        val body = ReportApi.buildReportForm(1L, 1, 2L, reasonOf(0), "abc").toString()
        assertFalse("csrf 不该出现在纯函数里", paramsOf(body).containsKey("csrf"))
        assertEquals("同样的入参必须给出同样的串", body, ReportApi.buildReportForm(1L, 1, 2L, reasonOf(0), "abc").toString())
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
        assertTrue(ReportApi.errorMsg(-352).isNotEmpty())
        assertTrue(ReportApi.errorMsg(-412).isNotEmpty())
    }

    @Test
    fun errorMsg_knownReportErrorsAreNotGeneric() {
        // 全部取自 bilibili-API/docs/comment/action.md「举报评论」的 json 回复段
        val known = listOf(
            -101, -102, -111, -352, -412, -400, -403, -404, -500, -509,
            12002, 12006, 12008, 12009, 12019, 12026, 12077
        )
        for (code in known) {
            val msg = ReportApi.errorMsg(code)
            assertTrue("code=$code 应有专属提示", msg.isNotEmpty())
            assertFalse("code=$code 不该落到兜底文案", msg.startsWith("操作失败（错误码"))
        }
    }

    @Test
    fun errorMsg_alreadyReportedSaysSo() {
        // 12008 是举报链路最高频的拒绝（同一评论只能报一次）
        assertTrue("12008 要说清是重复举报", ReportApi.errorMsg(12008).contains("举报过"))
    }

    @Test
    fun errorMsg_reasonLengthIsDistinguishedFromOtherErrors() {
        // 12077 是正文长度不合规：界面按 2~200 拦过一道，但服务端口径变了仍会走到这里
        val msg = ReportApi.errorMsg(12077)
        assertTrue("12077 要提到「理由」", msg.contains("理由"))
        assertNotEquals("12077 不能和 12008 混为一谈", ReportApi.errorMsg(12008), msg)
    }

    @Test
    fun errorMsg_cooldownCodesAreDistinguishable() {
        // 12019（举报过于频繁）与 12026（客户端「CD时间未到」）都是冷却拒绝，
        // 但给同一句话会让看日志的人分不清撞的是哪个码
        assertNotEquals(ReportApi.errorMsg(12019), ReportApi.errorMsg(12026))
        assertTrue(ReportApi.errorMsg(12019).contains("稍后"))
        assertTrue(ReportApi.errorMsg(12026).contains("稍后"))
    }

    @Test
    fun errorMsg_bannedAccountIsNotMistakenForLogout() {
        // -102（封停）与 -101（未登录）用户要做的事完全不同：一个去登录没用
        assertNotEquals(ReportApi.errorMsg(-101), ReportApi.errorMsg(-102))
        assertTrue("封停要出现「封」", ReportApi.errorMsg(-102).contains("封"))
    }

    @Test
    fun errorMsg_unknownCodeKeepsTheNumber() {
        // 遇到没见过的码，提示里必须带原始数字，否则用户/开发者没法定位
        assertTrue(ReportApi.errorMsg(99999).contains("99999"))
    }
}
