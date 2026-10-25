package com.RobinNotBad.BiliClient.service.download

/**
 * 分片（多线程）下载的判定规则与看门狗阈值。
 *
 * 分片下载容易"卡住"的几种情况都收敛在这里判断，纯逻辑、不依赖 Android，
 * 便于脱离设备跑 JVM 单测（见 app/src/test/.../SegmentDownloadPolicyTest.kt）：
 * 1. 服务端忽略 Range，回 200 + 整个文件：按分片读等于每片都读整文件，
 *    5 片就是 5 倍流量，慢网下表现为下载长时间不动；
 * 2. 连接建立了但服务端不出字节（h2 流排队、被限速到 0、代理黑洞）；
 * 3. 分片线程撞上 OkHttp 的 16s 读超时要重试 3 轮，最坏 48s 进度纹丝不动。
 *
 * 命中 1、2 时调用方应中止分片、退回单连接下载，并在本次进程内不再分片。
 */
internal object SegmentDownloadPolicy {

    /**
     * 任一存活分片超过这么久没有新字节，就判定这条链路不可用。
     * 取值依据：OkHttp connectTimeout 8s，留给建连 + TLS + 首字节的余量后取 12s；
     * 一旦超过它，继续等只会让用户看到"卡住"，不如直接换单连接重下。
     */
    const val STALL_TIMEOUT_MS = 12_000L

    /**
     * 判定异常后取消分片、等待线程退出的预算。
     * 必须在"取消 Call"之后用，且超时了就不能再起单线程重下——两拨线程写同一个文件必然损坏。
     */
    const val ABORT_JOIN_TIMEOUT_MS = 3_000L

    /** 分片自然结束时等待线程收尾的总预算（沿用审计 M11-g 的 30s，不按线程累加）。 */
    const val FINISH_JOIN_TIMEOUT_MS = 30_000L

    const val HTTP_OK = 200
    const val HTTP_PARTIAL_CONTENT = 206

    /** 距最后一次收到字节已经超过 [STALL_TIMEOUT_MS]。 */
    @JvmStatic
    fun hasStalled(lastByteAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastByteAtMs >= STALL_TIMEOUT_MS

    /** 分片响应必须是 206 Partial Content。 */
    @JvmStatic
    fun isPartialContent(code: Int): Boolean = code == HTTP_PARTIAL_CONTENT

    /** 服务端忽略了 Range，回的是整个文件（HTTP 200）。 */
    @JvmStatic
    fun isRangeIgnored(code: Int): Boolean = code == HTTP_OK

    /**
     * 从 `bytes 0-1023/51973319` 里取起点。
     * 拿不到（缺头 / 格式不认识"bytes *"）返回 null，表示不做强判。
     */
    @JvmStatic
    fun parseContentRangeStart(header: String?): Long? {
        val text = header?.trim() ?: return null
        if (text.length < 5 || !text.startsWith("bytes", ignoreCase = true)) return null
        val range = text.substring(5).trim().substringBefore('/').trim()
        if (range.isEmpty()) return null
        return range.substringBefore('-').trim().toLongOrNull()
    }

    /** Content-Range 的起点和请求的分片起点不一致，说明这段响应不是我们要的那一片。 */
    @JvmStatic
    fun isContentRangeMismatch(header: String?, expectedStart: Long): Boolean {
        val actual = parseContentRangeStart(header) ?: return false
        return actual != expectedStart
    }

    /** 这次失败是否说明"分片模式在当前网络下不可用"，需在本次进程内整体退化为单连接。 */
    @JvmStatic
    fun shouldDisableSegmentMode(rangeUnsupported: Boolean, stalled: Boolean): Boolean =
        rangeUnsupported || stalled
}
