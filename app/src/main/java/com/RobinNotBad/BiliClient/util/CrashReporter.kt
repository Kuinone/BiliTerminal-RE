package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.util.UiText

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.api.TerminalApi
import com.RobinNotBad.BiliClient.model.ApiResult
import org.json.JSONObject

/**
 * 崩溃报告的采集与上传。
 *
 * <p>分工：[com.RobinNotBad.BiliClient.ErrorCatch] 在**崩溃瞬间**（主进程，随时会死）
 * 只做最便宜的事——把异常类名、消息、线程名和页面轨迹塞进 Intent；
 * 真正的采集与上传放在崩溃页所在的 `:error_activity` 进程里做（见
 * [com.RobinNotBad.BiliClient.activity.CatchActivity]）。那边内存、文件、网络都是干净的，
 * 不会把「崩溃页都打不开」变成第二种崩溃。
 *
 * <p>上传是**尽力而为**：任何异常都吞掉并返回失败 [ApiResult]，绝不能让崩溃页再崩一次。
 */
object CrashReporter {

    /**
     * 页面轨迹与应用日志两段之间的分隔行。
     *
     * <p>两段共用 `log` 字段后，管理台里看到的就是一坨连续文本，没有这行分隔根本分不清
     * 哪几行是「用户走过哪些页面」、哪几行是「应用日志」——所以分隔符的用途就是**人眼定位**。
     * 前后各留一个空行（`\n\n` / `\n`），是让它在纯文本 / 等宽字体里也自成一段。
     */
    private const val LOG_SECTION_SEPARATOR = "\n\n--- 应用日志（内存缓冲，最近若干行）---\n"

    /**
     * 把页面轨迹与应用日志合并进同一个 `log` 字段。
     *
     * <p>**为什么复用同一个字段而不是新增一个**：
     * 1. 服务端 `crash` 表的 `log` 列上限 200000 字符，两段拼起来也远够用，于是**服务端零改动**
     *    ——不用发新表结构、不用改管理台、不用改导出脚本；
     * 2. 老数据（只有轨迹）与新数据（轨迹 + 应用日志）在库里**同构**，消费方不需要区分两种格式，
     *    顶多是一次 `indexOf` 看有没有分隔符。
     *
     * <p>**任一段为空时不留下孤零零的分隔符**：只有一段就直接用那一段，两段都空就是空串。
     */
    private fun mergeLog(trail: String?, ringLog: String?): String {
        val t = trail.orEmpty()
        val r = ringLog.orEmpty()
        return when {
            t.isEmpty() -> r
            r.isEmpty() -> t
            else -> t + LOG_SECTION_SEPARATOR + r
        }
    }

    /** 从 Intent extra 里带过来的那些「崩溃瞬间才拿得到」的字段。 */
    @JvmStatic
    fun buildPayload(
        context: Context?,
        exception: String?,
        message: String?,
        thread: String?,
        stack: String?,
        trail: String?,
        ringLog: String?,
        uptimeSec: Long,
        extra: String? = null
    ): JSONObject {
        // 安全审计 P2-9：只取总内存用于**分档**，不再上报精确的 ram_total / ram_avail。
        // 精确字节数等于给机型再加一枚指纹，而统计只关心「手表 / 手机大概多大内存」。
        // 档位规则在 TerminalApi.ramLevel（纯函数，有 JVM 单测）；取不到就是 UNKNOWN(0)。
        val ramLevel = try {
            val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (manager == null) {
                TerminalApi.RAM_LEVEL_UNKNOWN
            } else {
                val info = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                TerminalApi.ramLevel(info.totalMem)
            }
        } catch (ignored: Exception) {
            // 采集内存失败不影响报告主体
            TerminalApi.RAM_LEVEL_UNKNOWN
        }

        val mid = if (SharedPreferencesUtil.getBoolean(SettingsKeys.FEEDBACK_ATTACH_MID, false))
            SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) else 0L

        return TerminalApi.buildCrashPayload(
            /* installId = */ TerminalApi.getInstallId(),
            /* mid = */ mid,
            /* exception = */ exception,
            /* message = */ message,
            /* thread = */ thread,
            /* stack = */ stack,
            /* log = */ mergeLog(trail, ringLog),
            /* extra = */ extra ?: "",
            /* versionCode = */ BuildConfig.VERSION_CODE,
            /* versionName = */ BuildConfig.VERSION_NAME,
            /* sdk = */ Build.VERSION.SDK_INT,
            /* release = */ Build.VERSION.RELEASE,
            /* brand = */ Build.BRAND,
            /* device = */ Build.DEVICE,
            /* product = */ Build.PRODUCT,
            /* model = */ Build.MODEL,
            /* abi = */ TerminalApi.firstAbi(),
            /* ramLevel = */ ramLevel,
            /* uptimeSec = */ uptimeSec,
            /* isCoexist = */ BuildConfig.COEXIST
        )
    }

    /**
     * 采集 + 上传。返回成功时 `code` 就是给用户看的报错编号。
     *
     * @param trail 崩溃前用户走过的页面轨迹（`CrashTrail.snapshot()`）。
     * @param ringLog 内存日志快照（`Logu.ringSnapshot()`）；不需要附带时传空串。
     *   与 [trail] 合并进同一个 `log` 字段，理由见 [mergeLog]。
     */
    @JvmStatic
    fun upload(
        context: Context?,
        exception: String?,
        message: String?,
        thread: String?,
        stack: String?,
        trail: String?,
        ringLog: String?,
        uptimeSec: Long
    ): ApiResult {
        return try {
            val payload = buildPayload(context, exception, message, thread, stack, trail, ringLog, uptimeSec)
            TerminalApi.uploadCrash(payload)
        } catch (e: Exception) {
            ApiResult(-4, UiText.of("崩溃报告生成失败"))
        }
    }

    /** 进程已运行时长（秒）。放在这里以便崩溃页与反馈页共用同一套取值逻辑。 */
    @JvmStatic
    fun uptimeSeconds(): Long = SystemClock.elapsedRealtime() / 1000
}