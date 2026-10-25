package com.RobinNotBad.BiliClient.service.download

/**
 * 分片（Range）下载的响应校验，纯函数，可直接跑 JVM 单测。
 *
 * <h3>为什么必须校验</h3>
 * 分片下载靠 `Range: bytes=start-end` 让服务端只回这一段，客户端再
 * `RandomAccessFile.seek(start)` 写进文件的对应位置。一旦服务端**没按请求返回**
 * （忽略 Range 回整个文件、或返回的区间起点不是我们请求的那个），客户端仍会从
 * `seek(start)` 开始写——字节数可能正好对得上，校验通过，**产出一个错位的坏文件**。
 * 表现就是「多线程下载完成后播不了 / 拼不起来」。
 *
 * 所以判据不认"HTTP 成功"，只认**206 + 区间起点与请求一致**；拿不准就判失败，
 * 由调用方回退整文件单线程重下（慢一点，但不会产出坏文件）。
 */
internal object SegmentResponsePolicy {

    /** 分片请求期望的 HTTP 状态：206 Partial Content。200（忽略 Range）一律不接受。 */
    const val HTTP_PARTIAL_CONTENT = 206

    /**
     * 解析 `Content-Range: bytes 0-2097151/12345678` 里的起点与总长度。
     *
     * 只取 [ResponseContentRange.start] 与 [ResponseContentRange.total]：
     * `total` 为 `*`（服务端不报总长）时给 -1。
     * 格式不认识（缺 `bytes`、缺 `/`、数字非法）返回 null。
     */
    fun parseContentRange(value: String?): ResponseContentRange? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        if (!text.startsWith("bytes", ignoreCase = true)) return null
        val body = text.substring(5).trim()
        val slash = body.indexOf('/')
        if (slash <= 0 || slash == body.length - 1) return null
        val rangePart = body.substring(0, slash).trim()
        val totalPart = body.substring(slash + 1).trim()
        val dash = rangePart.indexOf('-')
        if (dash <= 0 || dash == rangePart.length - 1) return null
        val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
        if (start < 0 || end < start) return null
        val total = if (totalPart == "*") -1L else (totalPart.toLongOrNull() ?: return null)
        return ResponseContentRange(start, end, total)
    }

    /**
     * 这次分片响应能不能用来写入。
     *
     * @param httpCode     响应状态码
     * @param contentRange `Content-Range` 头原文，缺失传 null
     * @param requestedStart 我们请求的区间起点
     * @param expectedLen 请求的区间长度（闭区间，end - start + 1）
     * @return true 表示服务端确实只回了这一段，可以 `seek(requestedStart)` 写入
     */
    fun isPartialResponseUsable(
        httpCode: Int,
        contentRange: String?,
        requestedStart: Long,
        expectedLen: Long
    ): Boolean {
        if (httpCode != HTTP_PARTIAL_CONTENT) return false
        val range = parseContentRange(contentRange)
        // 合法的 206 必须带 Content-Range；缺失就说明这个服务端的行为不可信
        if (range == null) return false
        if (range.start != requestedStart) return false
        val actualLen = range.end - range.start + 1
        return actualLen <= expectedLen
    }

    /**
     * 分片读满了请求长度、且没多读，才算这一片成功。
     *
     * @param written 实际写入文件的字节数
     * @param expectedLen 请求的区间长度
     */
    fun isSegmentComplete(written: Long, expectedLen: Long): Boolean =
        expectedLen > 0 && written == expectedLen

    /** 解析后的 `Content-Range`。 */
    data class ResponseContentRange(val start: Long, val end: Long, val total: Long)
}
