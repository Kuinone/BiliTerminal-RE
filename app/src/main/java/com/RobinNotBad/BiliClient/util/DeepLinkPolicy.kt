package com.RobinNotBad.BiliClient.util

import java.util.Locale

/**
 * 外部深链的白名单策略（安全审计 P2-7）。
 *
 * 背景：[com.RobinNotBad.BiliClient.activity.GetIntentActivity] 是导出组件（`exported="true"`），
 * 清单里注册了 `bilibili://` 自定义 scheme 与不带 host 限定的 http/https。任何应用都能把任意 URL
 * 塞进 `Intent.data` 拉它起来；页面若直接按 `uri.host` 分派，等于把外部输入当命令解析。
 *
 * 本策略把「这个 scheme + host 组合允不允许处理」收敛成纯函数（不碰 Android API），便于单测：
 * - `bilibili://…`：本项目自己的 scheme，放行（host 在这里是 `video` / `article` 这类路径段）；
 * - `http(s)://`：只允许 `bilibili.com` 及其子域、以及短链 `b23.tv`，大小写、前导点、末尾点、
 *   带端口都按规范形式比较；
 * - 其余（`javascript` / `file` / `content` / 无 scheme / 站外域 / 伪装域）一律拒绝。
 *
 * 域规范化复用 [CookieDomainPolicy.normalizeHost]，两者是同一套「什么算 B 站主机」的判定，
 * 避免深链放行的域和收 Cookie 的域悄悄分叉。
 */
object DeepLinkPolicy {

    /** 本项目自定义 scheme。 */
    const val CUSTOM_SCHEME = "bilibili"

    /** B 站短链域（`https://b23.tv/xxxx`），只有这一个精确主机名，没有子域。 */
    const val SHORT_LINK_HOST = "b23.tv"

    private val WEB_SCHEMES = setOf("http", "https")

    /** 深链的判定结果。 */
    enum class Decision {
        /** `bilibili://…`，按自定义 scheme 的路径语义解析。 */
        CUSTOM_SCHEME,

        /** `http(s)://` + 白名单域。 */
        WEB_LINK,

        /** 不在白名单内，调用方应提示并结束。 */
        REJECTED
    }

    /**
     * 判定一个深链能否处理。
     *
     * @param scheme `Uri.scheme`（可能为 null：`content://` 之外的相对 URI 很常见）
     * @param host   `Uri.host`；自定义 scheme 下这里是路径里的第一段（`bilibili://video/1` → `video`）
     */
    fun decisionOf(scheme: String?, host: String?): Decision {
        val s = scheme?.trim()?.lowercase(Locale.ROOT)
        if (s.isNullOrEmpty()) return Decision.REJECTED
        if (s == CUSTOM_SCHEME) return Decision.CUSTOM_SCHEME
        if (s !in WEB_SCHEMES) return Decision.REJECTED
        return if (isAllowedWebHost(host)) Decision.WEB_LINK else Decision.REJECTED
    }

    /** http/https 深链的主机是否在白名单内（`bilibili.com` / 任意子域 / `b23.tv`）。 */
    fun isAllowedWebHost(rawHost: String?): Boolean {
        val host = CookieDomainPolicy.normalizeHost(rawHost) ?: return false
        return host == SHORT_LINK_HOST || CookieDomainPolicy.isBilibiliDomain(host)
    }
}
