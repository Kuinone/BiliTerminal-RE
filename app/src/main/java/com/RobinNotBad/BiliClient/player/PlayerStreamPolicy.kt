package com.RobinNotBad.BiliClient.player

/**
 * 「播放取流」的纯策略：什么时候必须走 DASH、DASH 结果够不够起播、外部音轨是网络流还是本地文件。
 *
 * 背景（26.10.30 修「播放器里切不到 1080P」）：
 * - 老取流接口 `fnval=1` 拿回的是**音视频合一**的 MP4，清晰度天花板就是 720P；请求 `qn=80`
 *   会被服务端静默降级成 `quality=64`（实测结论见 `docs/bilibili-api-notes.md`）。
 * - 1080P 及以上只有 DASH 拿得到，而 DASH 是「一条纯视频 + 一条纯音频」，播放侧必须起两路
 *   播放器：视频走 ijk，音频走 `PlayerActivity` 里的外部音轨那一路。
 *
 * 这些判据在取流层（[com.RobinNotBad.BiliClient.api.PlayerApi]）、入口
 * （[com.RobinNotBad.BiliClient.activity.video.JumpToPlayerActivity]）与播放器
 * （[com.RobinNotBad.BiliClient.activity.player.PlayerActivity]）三处共用，抽成纯函数是为了
 * 能单测：真机验证 1080P 需要登录态，判据写反了不该等到那时才发现。
 */
object PlayerStreamPolicy {
    /** 720P 的 qn 值。不高于它的清晰度继续用合一 MP4，避免给绝大多数播放引入双播放器。 */
    const val QN_720P = 64

    /** 这条清晰度是否必须走 DASH 才拿得到。 */
    @JvmStatic
    fun requiresDash(quality: Int): Boolean = quality > QN_720P

    /**
     * DASH 取流的结果是否足以起播：视频流与音轨都必须有值。
     *
     * 缺音轨时不能硬播 —— 那样画面正常但完全没声音，而且播放器里没有任何补救入口；
     * 宁可回退到 720P 的合一 MP4（有声有画）。纯静音稿件的合一流里同样带一条静音音轨，
     * 所以这个回退不会让静音视频变哑。
     */
    @JvmStatic
    fun isPlayableDash(videoUrl: String?, audioUrl: String?): Boolean =
        !videoUrl.isNullOrBlank() && !audioUrl.isNullOrBlank()

    /**
     * 外部音轨是网络流（在线 DASH）还是本地文件（缓存的 DASH 双文件）。
     *
     * 两者用同一条 `audio_track_url` 传进播放器，但初始化方式完全不同：本地文件可以同步
     * `prepare()`、也不该带请求头；网络流必须带 Referer/Cookie 并走 `prepareAsync()`。
     */
    @JvmStatic
    fun isRemoteAudioTrack(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
