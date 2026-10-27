package com.RobinNotBad.BiliClient.util

import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Calendar
import java.util.TimeZone

/**
 * 系统时间防呆（26.10.27）。
 *
 * <p><b>为什么要有它</b>：不插卡、关了自动对时的设备（三防机 Sonim XP5800 实测）电池耗尽后，系统时间会回到
 * 出厂默认值（常见 1970 / 2015 年）。这时所有 HTTPS 证书都被判成「尚未生效」，B 站、Gitee 一起连不上，
 * 系统浏览器弹「Security warning」，应用内只看到一句「网络错误」，用户根本猜不到是时间的问题。
 * 正确的解法是让用户把时间改对，<b>绝不是</b>放宽证书校验（见 `docs/agents/security.md`）。
 *
 * <p>两道判据：
 * <ul>
 *   <li>[isClockBehind]：当前时间早于本包的构建日期 → 时间一定错了（包不可能来自未来）。
 *       构建日期由 versionCode（YYMMDD0）反推，不另加 BuildConfig 字段，免得每次构建都让缓存失效。</li>
 *   <li>[isCertTimeError]：异常链里有证书「尚未生效 / 已过期」→ 多半是时间错了。</li>
 * </ul>
 *
 * <p>本类是纯函数，不读资源、不碰 Context，JVM 单测直接调用。
 */
object ClockSanity {

    /** 构建日期往前再放宽一天，抵消时区差（构建机东八区、设备可能在西半球）。 */
    private const val TOLERANCE_MS = 24L * 60 * 60 * 1000

    /** 异常链最多往下找几层，防止循环 cause。 */
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * 本机与服务器时间差超过多少算「时间不对」。证书有效期以天计，几分钟的漂移不会导致校验失败，
     * 阈值放宽到 1 小时，避免把网络延迟、服务器自身误差当成问题。
     */
    const val SKEW_THRESHOLD_MS = 60L * 60 * 1000

    /**
     * 本机时间与服务器时间是否差得离谱（早晚都算）。
     *
     * 服务器时间来自明文 http（见 `api/ServerTimeApi`），可被篡改，<b>只能用来给提示</b>，不参与任何校验。
     */
    @JvmStatic
    fun isSkewed(deviceMillis: Long, serverMillis: Long): Boolean {
        return Math.abs(deviceMillis - serverMillis) > SKEW_THRESHOLD_MS
    }

    /**
     * versionCode（YYMMDD0，如 2610270）→ 当天 00:00（UTC）的毫秒时间戳。
     * 不符合格式（月日越界、位数不对）返回 null，调用方当作「无法判断」。
     */
    @JvmStatic
    fun buildDateMillis(versionCode: Int): Long? {
        val yymmdd = versionCode / 10
        val yy = yymmdd / 10000
        val mm = yymmdd / 100 % 100
        val dd = yymmdd % 100
        if (versionCode < 1_000_000 || yy !in 20..99 || mm !in 1..12 || dd !in 1..31) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.isLenient = false
        cal.clear()
        cal.set(2000 + yy, mm - 1, dd)
        return try {
            cal.timeInMillis
        } catch (e: IllegalArgumentException) {
            null // 2 月 30 日之类
        }
    }

    /** 当前时间是否明显早于构建日期。versionCode 解析不了时返回 false（宁可不提示，也不误报）。 */
    @JvmStatic
    fun isClockBehind(nowMillis: Long, versionCode: Int): Boolean {
        val build = buildDateMillis(versionCode) ?: return false
        return nowMillis < build - TOLERANCE_MS
    }

    /**
     * 异常链里是否有证书时间类错误。
     *
     * 除了标准的两个异常类，还按消息兜底：Android 的 Conscrypt 有时把它们包成
     * `CertPathValidatorException: timestamp check failed` 或 `Could not validate certificate: ... not valid until/after`，
     * 原始异常不一定挂在 cause 上。
     */
    @JvmStatic
    fun isCertTimeError(e: Throwable?): Boolean {
        var cur = e
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            if (cur is CertificateNotYetValidException || cur is CertificateExpiredException) return true
            val msg = cur.message?.lowercase()
            if (msg != null && (
                    msg.contains("timestamp check failed") ||
                    msg.contains("not valid until") ||
                    msg.contains("not valid after") ||
                    msg.contains("certificate expired") ||
                    msg.contains("certificate not yet valid"))
            ) return true
            if (cur.cause === cur) break
            cur = cur.cause
            depth++
        }
        return false
    }
}
