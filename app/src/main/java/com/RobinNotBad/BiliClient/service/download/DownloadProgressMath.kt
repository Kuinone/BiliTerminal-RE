package com.RobinNotBad.BiliClient.service.download

/**
 * 下载进度与分片划分的纯计算。
 *
 * 这些表达式原本内联在 DownloadService 的下载循环里（进度换算 7 处、伪进度 2 处、
 * 分片划分 2 处）。抽成纯函数后可以脱离 Android 跑 JVM 单测，
 * 同时表达式保持与原实现逐字符一致，浮点结果不会因为改写顺序而变化。
 *
 * 无 Android 依赖。
 */
internal object DownloadProgressMath {

    /** 动态分片的目标片大小：约每 2MB 一片 */
    private const val SEGMENT_TARGET_BYTES = 2 * 1024 * 1024L

    /** 未知总大小时伪进度的步长 */
    private const val PSEUDO_STEP = 0.02f

    /** 未知总大小时伪进度的封顶值 */
    private const val PSEUDO_MAX = 0.9f

    // ---------- DASH 下载各阶段在整条进度条上的边界 ----------
    // 附件（封面/字幕/弹幕）0 → 0.12、视频 0.12 → 0.52、音频 0.52 → 0.88、合并 0.88 → 1.00。
    // 合并阶段只做容器重封装（不重新编码），耗时通常远小于音频下载，所以权重给小一点。

    /** DASH 附件阶段（封面/字幕/弹幕）的终点 */
    const val DASH_ATTACH_END = 0.12f

    /** DASH 视频流阶段的终点 */
    const val DASH_VIDEO_END = 0.52f

    /** DASH 音频流阶段的终点，也是合并阶段的起点 */
    const val DASH_AUDIO_END = 0.88f

    /** DASH 整条进度条的终点（合并完成） */
    const val DASH_MERGE_END = 1.0f

    /**
     * 分片数：约每 2MB 一片，且不超过用户配置的分片数上限，最少 1 片。
     *
     * 恒 ≥1 是硬要求——[segmentLen] 会拿它做除数，返回 0 会导致除零。
     * 用户配置被改成 0 或负数时同样兜底为 1 片。
     */
    @JvmStatic
    fun segmentCount(totalSize: Long, maxSplit: Int): Int {
        var segments = Math.min(maxSplit, Math.max(1, (totalSize / SEGMENT_TARGET_BYTES).toInt()))
        if (segments < 1) segments = 1
        return segments
    }

    /** 每片的字节数（整除，余数留给最后一节） */
    @JvmStatic
    fun segmentLen(totalSize: Long, segments: Int): Long = totalSize / segments

    /**
     * 第 [i] 片在文件中的闭区间 `[start, end]`。
     * 最后一节吃掉整除余数（`end = totalSize - 1`），保证所有分片恰好覆盖整个文件。
     */
    @JvmStatic
    fun segmentRange(i: Int, segments: Int, totalSize: Long, segmentLen: Long): LongRange {
        val start = i * segmentLen
        val end = if (i == segments - 1) totalSize - 1 else start + segmentLen - 1
        return start..end
    }

    /**
     * 已下载 [downloaded] 字节、总大小 [total] 时，映射到 `[baseProgress, endProgress]`
     * 区间内的进度值。
     *
     * 调用方需保证 `total > 0`（总大小未知的路径走 [progressForPseudo]）：
     * `total == 0` 时按浮点语义得到 Infinity/NaN，与原内联表达式一致。
     */
    @JvmStatic
    fun progressForBytes(downloaded: Long, total: Long, baseProgress: Float, endProgress: Float): Float {
        return baseProgress + (1.0f * downloaded / total) * (endProgress - baseProgress)
    }

    /** 推进一次伪进度并封顶（总大小未知时使用，避免进度条瞬间满或卡死） */
    @JvmStatic
    fun pseudoProgressStep(current: Float): Float = Math.min(current + PSEUDO_STEP, PSEUDO_MAX)

    /** 把伪进度映射到 `[baseProgress, endProgress]` 区间内的进度值 */
    @JvmStatic
    fun progressForPseudo(pseudoProgress: Float, baseProgress: Float, endProgress: Float): Float {
        return baseProgress + (endProgress - baseProgress) * pseudoProgress
    }

    /**
     * 把合并阶段内部进度 [fraction]（0..1）映射到进度条的 `[DASH_AUDIO_END, DASH_MERGE_END]` 区间。
     *
     * 合并进度由 [com.RobinNotBad.BiliClient.util.MediaMerger] 的回调给出，已读字节比例为 0..1；
     * 越界值 clamp 掉，避免进度条冲过 100%。
     */
    @JvmStatic
    fun dashMergeProgress(fraction: Float): Float {
        return DASH_AUDIO_END + (DASH_MERGE_END - DASH_AUDIO_END) * fraction.coerceIn(0f, 1f)
    }
}
