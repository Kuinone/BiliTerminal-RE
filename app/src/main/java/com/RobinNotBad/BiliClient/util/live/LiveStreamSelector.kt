package com.RobinNotBad.BiliClient.util.live

import com.RobinNotBad.BiliClient.model.LivePlayInfo

/**
 * 直播取流候选选择：把直播间播放信息里的所有可取地址摊平成一个**有序候选表**。
 *
 * 背景（详见 docs/archive/superpowers/specs/2026-10-11-player-network-resilience.md 的 P0-2/P2-3）：
 * 原实现固定取 `stream[0].format[0].codec[0].url_info[selectedHost]`，即永远是
 * 「HTTP-FLV 的第一条 CDN 线路」。B 站会把 HLS 那一路（`stream[1]`，format_name = ts/fmp4）
 * 一起下发，代码却从不用它；某条 CDN 挂掉时也没有任何自动换线路的能力。
 *
 * 本类只做「摊平 + 排序 + 去重」，不碰网络、不碰 Android API，因此可以直接 JVM 单测。
 * 排序规则：**FLV（http_stream）在前、HLS（ts/fmp4）在后**，同组内保持接口返回的顺序
 * （所以 url_info 的线路顺序也保持原样）。这样第一个候选与旧行为完全一致（不回归），
 * 后面的候选供看门狗做 CDN failover / HLS 兜底。
 */
object LiveStreamSelector {

    /** 一个可直接交给播放器的直播地址，以及它是怎么来的（便于日志定位是哪个协议/线路翻车） */
    data class Candidate(
        /** 已经拼好的完整地址（host + base_url + extra） */
        val url: String,
        /** 例如 http_stream / http_hls */
        val protocolName: String,
        /** 例如 flv / ts / fmp4 */
        val formatName: String,
        /** 例如 avc / hevc */
        val codecName: String,
        /** 该地址在所属 codec 的 url_info 里的下标（即"路线N"的 N，从 0 起） */
        val hostIndex: Int,
        val host: String,
        /** true = HLS 系（ts/fmp4），false = FLV 这类单长连接 */
        val isHls: Boolean
    )

    private const val PROTOCOL_HTTP_HLS = "http_hls"
    private const val FORMAT_FLV = "flv"
    private const val FORMAT_TS = "ts"
    private const val FORMAT_FMP4 = "fmp4"

    /**
     * 摊平出全部候选地址（已去重）。任何一层缺失（playUrl / stream / format / codec / url_info
     * 为 null 或空、host 或 base_url 为空白）都直接跳过，绝不抛异常——直播接口在
     * 「未开播 / 已下播 / 风控」等情况下就是会返回半截结构。
     */
    fun candidates(playInfo: LivePlayInfo?): List<Candidate> {
        val streams = playInfo?.playUrl?.stream ?: return emptyList()
        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()

        for (stream in streams) {
            if (stream == null) continue
            val protocolName = stream.protocol_name ?: ""
            val formats = stream.format ?: continue
            for (format in formats) {
                if (format == null) continue
                val formatName = format.format_name ?: ""
                val codecs = format.codec ?: continue
                for (codec in codecs) {
                    if (codec == null) continue
                    val codecName = codec.codec_name ?: ""
                    val baseUrl = codec.base_url
                    if (baseUrl.isNullOrBlank()) continue
                    val urlInfos = codec.url_info ?: continue
                    var hostIndex = -1
                    for (urlInfo in urlInfos) {
                        if (urlInfo == null) continue
                        hostIndex++
                        val host = urlInfo.host
                        if (host.isNullOrBlank()) continue
                        val url = host + baseUrl + (urlInfo.extra ?: "")
                        if (!seen.add(url)) continue
                        result += Candidate(
                            url = url,
                            protocolName = protocolName,
                            formatName = formatName,
                            codecName = codecName,
                            hostIndex = hostIndex,
                            host = host,
                            isHls = isHls(protocolName, formatName)
                        )
                    }
                }
            }
        }
        // 稳定排序：FLV 在前，HLS 在后；同组内保持接口给的顺序
        return result.sortedBy { if (it.isHls) 1 else 0 }
    }

    /** 首选地址（= 旧行为：HTTP-FLV 的第一条线路）；没有可用地址时返回 null */
    fun first(playInfo: LivePlayInfo?): Candidate? = candidates(playInfo).firstOrNull()

    /** 取第 [index] 个候选（越界返回 null），供看门狗按顺序换线路/换协议重试 */
    fun candidateAt(playInfo: LivePlayInfo?, index: Int): Candidate? = candidates(playInfo).getOrNull(index)

    /**
     * 界面上「路线N」列表用的候选：优先非 HLS（FLV）的线路，保持接口给的顺序；
     * 整个响应里没有 FLV 时退化成全部候选——纯 HLS 的直播间也要能列出线路，
     * 否则列表空着、用户点播放却仍能播，看起来像 bug。
     */
    fun routeCandidates(playInfo: LivePlayInfo?): List<Candidate> {
        val all = candidates(playInfo)
        val flv = all.filter { !it.isHls }
        return if (flv.isEmpty()) all else flv
    }

    /**
     * 供运行时 failover 使用的顺序：把用户选中的那条排到最前，其余按 [candidates] 的顺序跟上。
     * [selectedRoute] 是「路线N」列表的下标（从 0 起），越界时退回首选候选。
     */
    fun orderedForFailover(playInfo: LivePlayInfo?, selectedRoute: Int): List<Candidate> {
        val all = candidates(playInfo)
        val primary = routeCandidates(playInfo).getOrNull(selectedRoute)
            ?: all.firstOrNull()
            ?: return emptyList()
        return listOf(primary) + all.filter { it.url != primary.url }
    }

    /** ts / fmp4 或 http_hls 都算 HLS 系；其余（flv）算单长连接 */
    fun isHls(protocolName: String?, formatName: String?): Boolean {
        val format = formatName?.lowercase() ?: ""
        val protocol = protocolName?.lowercase() ?: ""
        return format == FORMAT_TS || format == FORMAT_FMP4 || protocol == PROTOCOL_HTTP_HLS
    }

    /** 是否是 FLV 系（便于日志说明"现在走的是哪一类"） */
    fun isFlv(formatName: String?): Boolean = formatName?.lowercase() == FORMAT_FLV
}
