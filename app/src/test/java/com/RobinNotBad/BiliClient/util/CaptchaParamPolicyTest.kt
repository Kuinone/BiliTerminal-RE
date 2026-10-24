package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-8：极验 gt / challenge 白名单的边界。
 *
 * 这里钉的不是「正常参数能过」——那只证明功能没坏；真正要钉的是**恶意参数过不去**：
 * 引号能闭合 JS 字符串、换行能另起一行代码、`</script>` 能提前结束脚本块，
 * 这三类只要漏一个，服务端下发的参数就等于可执行代码。
 *
 * 用例全部用「16 位合法字符 + 一个可疑尾巴」构造成 16 字符，正好卡在长度下限上，
 * 这样拒绝原因只可能是字符集，而不是长度——避免某天放宽长度时这些用例悄悄失效。
 */
class CaptchaParamPolicyTest {

    /** 构造一个长度恰为 16、以 [tail] 结尾的取值（tail 超长时原样返回）。 */
    private fun withTail(tail: String): String {
        val pad = 16 - tail.length
        return if (pad <= 0) tail else "a".repeat(pad) + tail
    }

    // ---------- 合法取值 ----------

    @Test
    fun officialGeetestHex_isAccepted() {
        // 极验官方 gt / challenge 都是 32 位十六进制
        val gt = "0123456789abcdef0123456789abcdef"
        val challenge = "fedcba9876543210fedcba9876543210"
        assertTrue(CaptchaParamPolicy.isValid(gt))
        assertTrue(CaptchaParamPolicy.isValid(challenge))
        assertTrue(CaptchaParamPolicy.areValid(gt, challenge))
    }

    @Test
    fun lengthBoundaries_areInclusive() {
        assertTrue("16 位是下限，应放行", CaptchaParamPolicy.isValid("a".repeat(16)))
        assertFalse("15 位太短", CaptchaParamPolicy.isValid("a".repeat(15)))
        assertTrue("64 位是上限，应放行", CaptchaParamPolicy.isValid("a".repeat(64)))
        assertFalse("65 位太长", CaptchaParamPolicy.isValid("a".repeat(65)))
    }

    @Test
    fun mixedCaseLettersAndDigits_areAccepted() {
        assertTrue(CaptchaParamPolicy.isValid("AbCdEf0123456789"))
        assertTrue(CaptchaParamPolicy.isValid("0123456789ABCDEF"))
    }

    // ---------- 空值 ----------

    @Test
    fun blankAndNull_areRejected() {
        assertFalse("null 不该被当成合法", CaptchaParamPolicy.isValid(null))
        assertFalse(CaptchaParamPolicy.isValid(""))
        assertFalse(CaptchaParamPolicy.isValid(" ".repeat(16)))
        assertFalse(CaptchaParamPolicy.areValid(null, null))
    }

    // ---------- 注入面 ----------

    @Test
    fun embeddedQuote_isRejected() {
        // `'; alert(1); //` 这类载荷的起点就是一个引号
        assertFalse(CaptchaParamPolicy.isValid(withTail("\"")))
        assertFalse(CaptchaParamPolicy.isValid("0123456789abcde\""))
        assertFalse(CaptchaParamPolicy.isValid(withTail("'")))
    }

    @Test
    fun newlineAndControlChars_areRejected() {
        assertFalse(CaptchaParamPolicy.isValid(withTail("\n")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("\r")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("\t")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("\\")))
    }

    @Test
    fun scriptCloseTag_isRejected() {
        // JSONObject.quote 不转义 </script>，只能靠白名单挡
        assertFalse(CaptchaParamPolicy.isValid("</script>"))
        assertFalse(CaptchaParamPolicy.isValid("0123456789</script>"))
        assertFalse(CaptchaParamPolicy.isValid("<script>alert(1)</script>"))
    }

    @Test
    fun punctuationAndSpaces_areRejected() {
        assertFalse(CaptchaParamPolicy.isValid(withTail(" ")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("-")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("_")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("/")))
        assertFalse(CaptchaParamPolicy.isValid(withTail(";")))
    }

    @Test
    fun nonAscii_isRejected() {
        assertFalse(CaptchaParamPolicy.isValid(withTail("中")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("é")))
        assertFalse(CaptchaParamPolicy.isValid(withTail("０"))) // 全角数字
    }

    // ---------- 双参数 ----------

    @Test
    fun areValid_requiresBothParams() {
        val ok = "0123456789abcdef"
        assertFalse("gt 合法、challenge 非法也要拒绝", CaptchaParamPolicy.areValid(ok, "短"))
        assertFalse("gt 非法、challenge 合法也要拒绝", CaptchaParamPolicy.areValid("短", ok))
        assertFalse(CaptchaParamPolicy.areValid(ok, null))
        assertFalse(CaptchaParamPolicy.areValid(null, ok))
    }
}
