package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 P2-7 回归测试：导出组件的深链入口必须按 scheme + host 白名单判定。
 *
 * 原 bug：GetIntentActivity 导出了 http/https 且不限定 host，任何应用都能把任意 URL 塞进
 * `Intent.data` 把它拉起来，页面再按 `uri.host` 直接分派。
 *
 * 这里锁住 [DeepLinkPolicy] 的判定；它不依赖 Android API，纯 JVM 可跑。
 */
class DeepLinkPolicyTest {

    // ---------------- 自定义 scheme：bilibili:// ----------------

    @Test
    fun decisionOf_customScheme_isAllowed() {
        assertEquals(
            DeepLinkPolicy.Decision.CUSTOM_SCHEME,
            DeepLinkPolicy.decisionOf("bilibili", "video")
        )
        assertEquals(
            DeepLinkPolicy.Decision.CUSTOM_SCHEME,
            DeepLinkPolicy.decisionOf("bilibili", "article")
        )
        // scheme 大小写不敏感（Uri 一般会规范化，但外部传入的字符串不能假设已规范化）
        assertEquals(
            DeepLinkPolicy.Decision.CUSTOM_SCHEME,
            DeepLinkPolicy.decisionOf("BILIBILI", null)
        )
    }

    // ---------------- http/https：只认 B 站域与短链 ----------------

    @Test
    fun decisionOf_webLinksOnBilibiliDomains_areAllowed() {
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("https", "www.bilibili.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("http", "bilibili.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("https", "B23.TV")
        )
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("https", "b23.tv")
        )
        // 带端口、末尾点写法都要能识别成同一个主机
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("https", "www.bilibili.com:443")
        )
        assertEquals(
            DeepLinkPolicy.Decision.WEB_LINK,
            DeepLinkPolicy.decisionOf("https", "www.bilibili.com.")
        )
    }

    @Test
    fun decisionOf_maliciousWebHosts_areRejected() {
        // 审计 P2-7 的核心：伪装域不能混进来
        assertEquals(
            DeepLinkPolicy.Decision.REJECTED,
            DeepLinkPolicy.decisionOf("https", "evilbilibili.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.REJECTED,
            DeepLinkPolicy.decisionOf("https", "bilibili.com.evil.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.REJECTED,
            DeepLinkPolicy.decisionOf("https", "b23.tv.evil.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.REJECTED,
            DeepLinkPolicy.decisionOf("https", "www.bilibili.com@evil.com")
        )
        assertEquals(
            DeepLinkPolicy.Decision.REJECTED,
            DeepLinkPolicy.decisionOf("https", "evil.com")
        )
    }

    @Test
    fun decisionOf_missingOrUnsupportedScheme_isRejected() {
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf(null, "bilibili.com"))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("", "bilibili.com"))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("javascript", "alert(1)"))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("file", "/sdcard/x"))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("content", "media"))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("ftp", "www.bilibili.com"))
    }

    @Test
    fun decisionOf_webSchemeWithoutHost_isRejected() {
        // 形如 "https:///video/1" 或相对 URI：没有 host，无法确认目标站点
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("https", null))
        assertEquals(DeepLinkPolicy.Decision.REJECTED, DeepLinkPolicy.decisionOf("http", ""))
    }

    // ---------------- isAllowedWebHost 单独校验 ----------------

    @Test
    fun isAllowedWebHost_matchesSubdomainsAndShortLink() {
        assertTrue(DeepLinkPolicy.isAllowedWebHost("space.bilibili.com"))
        assertTrue(DeepLinkPolicy.isAllowedWebHost("m.bilibili.com"))
        assertTrue(DeepLinkPolicy.isAllowedWebHost("b23.tv"))
        // b23.tv 没有子域：其它主机名不算
        assertFalse(DeepLinkPolicy.isAllowedWebHost("www.b23.tv"))
        assertFalse(DeepLinkPolicy.isAllowedWebHost("bili23.tv"))
        assertFalse(DeepLinkPolicy.isAllowedWebHost("i0.hdslb.com"))
        assertFalse(DeepLinkPolicy.isAllowedWebHost(null))
    }
}
