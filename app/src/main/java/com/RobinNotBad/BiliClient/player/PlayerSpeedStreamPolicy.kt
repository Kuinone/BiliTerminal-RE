package com.RobinNotBad.BiliClient.player

import kotlin.math.abs

/**
 * 「倍速 ↔ 取流」的纯策略：什么倍速下必须换成音视频合一的单文件、回到 1x 之后要不要换回高清 DASH。
 *
 * 背景（26.10.31 修「倍速播放时听到两路重叠的声音、还有一路跟不上」）：
 * - ijk 的变速是「音频时钟带动视频」实现的（soundtouch 处理音频，视频按音频时钟出帧）。
 *   DASH 的纯视频流**没有音轨**，`setSpeed` 下去视频照旧按 1x 走（模拟器实测 2x 请求下
 *   播放速率 1.000x / 1.001x，见 `docs/architecture-map.md` §7.57 的补充）。
 * - 而 1080P 及以上只有 DASH 拿得到，音频是另一路 `MediaPlayer`，它的 `PlaybackParams`
 *   立刻生效 → 两路时钟分叉，`progressChange` 的 800ms 校正只好每 ~1.3 秒把音频 seek 回去
 *   一次（实测 47 次/69 秒、每次回拉 700~1000ms），听感就是「两路声音重叠、有一路跟不上」。
 * - 所以**非 1x 时必须换成「自带音轨的单文件」**（`fnval=1` 的合一 MP4），由 ijk 自己同时
 *   变速音频与视频。这种流拿得到多高由服务端按账号权限决定（未登录实测被降到 720P），
 *   所以请求档位仍然用用户选的那一档，真实档位由响应里的 `quality` 回填（见
 *   `PlayerApi#applyActualQuality`）。回到 1x 再换回 DASH 高清档。
 *
 * 抽成纯函数是为了能单测：这里判反了不会崩，只会把叠声 bug 带回来、或者把用户锁在低档。
 */
object PlayerSpeedStreamPolicy {

    /** 浮点档位比较的容差（滑条档位都是 0.25 的整数倍，这里只是为了避开 1.0F 的比较误差）。 */
    private const val SPEED_EPSILON = 0.001F

    /** 这个倍速值是否等价于「原速 1.0x」。 */
    fun isNormalSpeed(speed: Float): Boolean = abs(speed - 1F) <= SPEED_EPSILON

    /**
     * 非 1x 时是否必须换成「音视频合一、不挂外部音轨」的流。
     *
     * 注意它只看倍速：真正需不需要重建播放器由调用方再判断「当前是否已经挂着外部音轨」，
     * 已经是合一 MP4 时就不用再切一次。
     */
    fun requiresCombinedStream(speed: Float): Boolean = !isNormalSpeed(speed)

    /**
     * 倍速下换流应该请求的清晰度。
     *
     * 就是用户选的那一档：不夹到 720P，是因为「合一 MP4 只有 720P」只是**未登录**下的实测天花板，
     * 登录态/大会员能拿到哪一档由服务端决定，请求时压低档位只会白白丢清晰度。真实档位
     * 拿到响应后按 `quality` 回填（`PlayerApi#applyActualQuality`）。
     */
    fun requestedQuality(userQuality: Int): Int = userQuality

    /**
     * 回到 1x 之后是否需要把流换回高清 DASH。
     *
     * 只有「倍速期间被临时压过档」才需要：用户选的档位高于 720P（那一档只可能是 DASH）、
     * 而当前挂的不是外部音轨（说明正在播合一 MP4）。
     */
    fun shouldRestoreDash(userQuality: Int, hasExternalAudio: Boolean, speed: Float): Boolean =
        isNormalSpeed(speed) && !hasExternalAudio && userQuality > PlayerStreamPolicy.QN_720P
}
