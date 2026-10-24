package com.RobinNotBad.BiliClient.util

import java.util.Locale

/**
 * Set-Cookie 的域策略（安全审计 P2-5）。
 *
 * 背景：原先 [NetWorkUtil] 用 `cookies.get("Domain").endsWith("bilibili.com")` 判断一条新 Cookie
 * 是否该收下，两个漏洞：
 * 1. `endsWith("bilibili.com")` 不看点边界，`evilbilibili.com`、`notbilibili.com` 都能过；
 * 2. 没有 `Domain` 属性时直接放行，等于任何响应来源下发的第一方 Cookie 都收。
 *
 * 本策略把「这条 Cookie 的域是否可接受」收敛成纯函数（只用 java.util.Locale，不碰 Android API），
 * 便于 JVM 单测。判定原则：
 * - 有 `Domain` 属性：必须是 `bilibili.com` 或它的子域（`api.bilibili.com` / `.bilibili.com`），
 *   其余一律拒绝（含 `bilibili.com.evil.com` 这种把 B 站域名放在中间/前缀的伪装）；
 * - 没有 `Domain` 属性：属于第一方 Cookie，只有响应来源本身是 B 站域才收（浏览器也是这么做的：
 *   不带 Domain 的 Cookie 只可能属于响应的那个主机）；
 * - 解析不出来的（空串、带路径/端口/用户信息等）一律 fail-closed。
 */
object CookieDomainPolicy {

    /** 唯一允许写入 Cookie 的站点域。 */
    const val BILIBILI_DOMAIN = "bilibili.com"

    /**
     * 从一条 `Set-Cookie` 里取 `Domain` 属性的值（原样返回，不规范化）。
     *
     * RFC 6265 规定属性名大小写不敏感，`Domain` / `domain` / `DOMAIN` 都算；
     * 值外面允许带双引号，这里一并去掉。没有该属性、或值为空时返回 null
     * （调用方 [shouldAccept] 会把 null 当作「第一方 Cookie」处理）。
     */
    @JvmStatic
    fun domainAttributeOf(setCookie: String?): String? {
        if (setCookie.isNullOrBlank()) return null
        for (part in setCookie.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val eq = item.indexOf('=')
            // eq == 0 说明没有属性名（形如 "=x"），跳过
            if (eq <= 0) continue
            if (!item.substring(0, eq).trim().equals("domain", ignoreCase = true)) continue
            var value = item.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1).trim()
            }
            return if (value.isEmpty()) null else value
        }
        return null
    }

    /**
     * 把各种来源的「主机名」规范成可比较的小写形式；无法确认是合法 DNS 主机名时返回 null。
     *
     * 处理：首尾空白、`Domain` 值两端的引号、前导点（`.bilibili.com`）、末尾点（FQDN 写法
     * `bilibili.com.`）、`host:port` 形式的端口、大小写。含 `@` `/` `\` 空格等字符的一律作废
     * （A 站的写法 `https://bilibili.com@evil.com` 不能靠切字符串蒙混过关）。
     */
    @JvmStatic
    fun normalizeHost(raw: String?): String? {
        if (raw == null) return null
        var host = raw.trim()
        if (host.isEmpty()) return null
        if (host.length >= 2 && host.first() == '"' && host.last() == '"') {
            host = host.substring(1, host.length - 1).trim()
        }
        // 去掉端口：只有「冒号后面全是数字」才当作端口，否则视为非法串（如 IPv6 字面量）
        val colon = host.lastIndexOf(':')
        if (colon >= 0) {
            val port = host.substring(colon + 1)
            if (port.isEmpty() || !port.all { it.isDigit() }) return null
            host = host.substring(0, colon)
        }
        host = host.lowercase(Locale.ROOT).trimEnd('.')
        // 前导点表示「子域树」写法，规范化时就当普通主机名比较
        host = host.trimStart('.')
        if (host.isEmpty()) return null
        for (ch in host) {
            val legal = ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-'
            if (!legal) return null
        }
        return host
    }

    /** 主机名是否属于 `bilibili.com`（含任意层子域，不含 `b23.tv` 等其它 B 站域）。 */
    @JvmStatic
    fun isBilibiliDomain(raw: String?): Boolean {
        val host = normalizeHost(raw) ?: return false
        return host == BILIBILI_DOMAIN || host.endsWith(".$BILIBILI_DOMAIN")
    }

    /**
     * 判断这条 Cookie 该不该收下。
     *
     * @param responseHost 该 `Set-Cookie` 所属响应的主机名（`response.request().url().host()`）
     * @param cookieDomain 该 Cookie 的 `Domain` 属性，没有则传 null
     * @return true 表示可以写入 Cookie 存储
     */
    @JvmStatic
    fun shouldAccept(responseHost: String?, cookieDomain: String?): Boolean {
        // 第一方 Cookie：没有 Domain 属性，只能来自 B 站域响应
        if (cookieDomain.isNullOrBlank()) return isBilibiliDomain(responseHost)
        // 带 Domain 属性：不管响应来自哪，域本身必须落在 bilibili.com 内
        return isBilibiliDomain(cookieDomain)
    }
}
