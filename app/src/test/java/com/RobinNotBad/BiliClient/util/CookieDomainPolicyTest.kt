package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 P2-5 回归测试：Set-Cookie 的域校验必须「看点边界」，且没有 Domain 时不能无条件收下。
 *
 * 原 bug：`cookies.get("Domain").endsWith("bilibili.com")`
 * - `evilbilibili.com` / `notbilibili.com` 都能通过（后缀相等但不看点边界）；
 * - 不带 Domain 属性的 Cookie 直接放行，任何来源都能写。
 *
 * 这里锁住 [CookieDomainPolicy] 的判定；它不依赖 Android API，纯 JVM 可跑。
 */
class CookieDomainPolicyTest {

    // ---------------- normalizeHost：各种写法先规范化 ----------------

    @Test
    fun normalizeHost_nullOrBlank_isNull() {
        assertNull(CookieDomainPolicy.normalizeHost(null))
        assertNull(CookieDomainPolicy.normalizeHost(""))
        assertNull(CookieDomainPolicy.normalizeHost("   "))
    }

    @Test
    fun normalizeHost_lowercasesAndStripsDots() {
        assertEquals("bilibili.com", CookieDomainPolicy.normalizeHost("BILIBILI.COM"))
        assertEquals("bilibili.com", CookieDomainPolicy.normalizeHost(".bilibili.com"))
        assertEquals("bilibili.com", CookieDomainPolicy.normalizeHost("bilibili.com."))
        assertEquals("api.bilibili.com", CookieDomainPolicy.normalizeHost("  .API.Bilibili.COM.  "))
    }

    @Test
    fun normalizeHost_stripsPortAndQuotes() {
        assertEquals("bilibili.com", CookieDomainPolicy.normalizeHost("bilibili.com:443"))
        assertEquals("bilibili.com", CookieDomainPolicy.normalizeHost("\"bilibili.com\""))
    }

    @Test
    fun normalizeHost_rejectsUserinfoAndPathTricks() {
        // "https://bilibili.com@evil.com" 这类写法不能靠切字符串混成合法主机
        assertNull(CookieDomainPolicy.normalizeHost("https://bilibili.com@evil.com"))
        assertNull(CookieDomainPolicy.normalizeHost("bilibili.com/evil"))
        assertNull(CookieDomainPolicy.normalizeHost("bilibili.com evil.com"))
        assertNull(CookieDomainPolicy.normalizeHost("::1"))
        assertNull(CookieDomainPolicy.normalizeHost("bilibili.com:abc"))
    }

    // ---------------- isBilibiliDomain：只认 bilibili.com 及其子域 ----------------

    @Test
    fun isBilibiliDomain_acceptsMainAndSubdomains() {
        assertTrue(CookieDomainPolicy.isBilibiliDomain("bilibili.com"))
        assertTrue(CookieDomainPolicy.isBilibiliDomain(".bilibili.com"))
        assertTrue(CookieDomainPolicy.isBilibiliDomain("api.bilibili.com"))
        assertTrue(CookieDomainPolicy.isBilibiliDomain("API.BILIBILI.COM"))
        assertTrue(CookieDomainPolicy.isBilibiliDomain("a.b.c.bilibili.com"))
    }

    @Test
    fun isBilibiliDomain_rejectsLookalikes() {
        // 审计 P2-5 的核心：无点边界的后缀匹配会让这些域通过
        assertFalse(CookieDomainPolicy.isBilibiliDomain("evilbilibili.com"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain("notbilibili.com"))
        // 把 B 站域名放在中间/前缀的伪装
        assertFalse(CookieDomainPolicy.isBilibiliDomain("bilibili.com.evil.com"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain("www.bilibili.com.evil.com"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain("bilibili.comm"))
    }

    @Test
    fun isBilibiliDomain_otherBiliDomainsAreNotCookieDomains() {
        // 短链、图床、备用域都不是「可以写 Cookie 的站点域」
        assertFalse(CookieDomainPolicy.isBilibiliDomain("b23.tv"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain("i0.hdslb.com"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain("bilibili.cn"))
        assertFalse(CookieDomainPolicy.isBilibiliDomain(null))
        assertFalse(CookieDomainPolicy.isBilibiliDomain(""))
    }

    // ---------------- domainAttributeOf：大小写不敏感地取 Domain 属性 ----------------

    @Test
    fun domainAttributeOf_readsTypicalSetCookie() {
        assertEquals(
            ".bilibili.com",
            CookieDomainPolicy.domainAttributeOf("SESSDATA=abc; Domain=.bilibili.com; Path=/; Secure; HttpOnly")
        )
    }

    @Test
    fun domainAttributeOf_isCaseInsensitiveAndUnquotes() {
        assertEquals("bilibili.com", CookieDomainPolicy.domainAttributeOf("buvid3=x; domain=bilibili.com; Path=/"))
        assertEquals(".bilibili.com", CookieDomainPolicy.domainAttributeOf("buvid3=x; DOMAIN=\".bilibili.com\""))
    }

    @Test
    fun domainAttributeOf_missingOrEmpty_isNull() {
        assertNull(CookieDomainPolicy.domainAttributeOf("SESSDATA=abc; Path=/; Secure"))
        assertNull(CookieDomainPolicy.domainAttributeOf("SESSDATA=abc; Domain="))
        assertNull(CookieDomainPolicy.domainAttributeOf(""))
        assertNull(CookieDomainPolicy.domainAttributeOf(null))
        // 值里出现 "; Domain=" 之类只是普通文本，不该被当属性
        assertNull(CookieDomainPolicy.domainAttributeOf("x=Domain=bilibili.com"))
    }

    // ---------------- shouldAccept：来源 + Domain 综合判定 ----------------

    @Test
    fun shouldAccept_firstPartyCookieFromBilibiliHost() {
        assertTrue(CookieDomainPolicy.shouldAccept("api.bilibili.com", null))
        assertTrue(CookieDomainPolicy.shouldAccept("www.bilibili.com", null))
        assertTrue(CookieDomainPolicy.shouldAccept("www.bilibili.com", ""))
    }

    @Test
    fun shouldAccept_firstPartyCookieFromLookalikeHost_isRejected() {
        assertFalse(CookieDomainPolicy.shouldAccept("evilbilibili.com", null))
        assertFalse(CookieDomainPolicy.shouldAccept("bilibili.com.evil.com", null))
        assertFalse(CookieDomainPolicy.shouldAccept("b23.tv", null))
        assertFalse(CookieDomainPolicy.shouldAccept("i0.hdslb.com", null))
        assertFalse(CookieDomainPolicy.shouldAccept(null, null))
    }

    @Test
    fun shouldAccept_domainCookieMustBeBilibili() {
        assertTrue(CookieDomainPolicy.shouldAccept("api.bilibili.com", ".bilibili.com"))
        assertTrue(CookieDomainPolicy.shouldAccept("api.bilibili.com", "bilibili.com"))
        assertFalse(CookieDomainPolicy.shouldAccept("api.bilibili.com", "evilbilibili.com"))
        assertFalse(CookieDomainPolicy.shouldAccept("api.bilibili.com", "bilibili.com.evil.com"))
        assertFalse(CookieDomainPolicy.shouldAccept("api.bilibili.com", "b23.tv"))
        assertFalse(CookieDomainPolicy.shouldAccept("api.bilibili.com", "not a host"))
    }
}
