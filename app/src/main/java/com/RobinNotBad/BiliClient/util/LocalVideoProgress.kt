package com.RobinNotBad.BiliClient.util

/**
 * 本地/缓存视频断点续播的位置策略（纯函数，不碰 Context、不碰文件，便于 JVM 单测）。
 *
 * 读与写刻意用**同一条判定**（`时长 - 位置 <= END_TOLERANCE_MS` 就算看完），
 * 否则会出现"存得进去、读不出来"的时灵时不灵：
 * - 位置不足 [MIN_RESUME_MS]：视为没有可续播的位置（进去几秒就退出，没必要续播）；
 * - 距结尾不足 [END_TOLERANCE_MS]：视为已看完，落盘写 0、下次从头播；
 * - 总时长未知（<= 0）时只按位置判断，不猜结尾。
 *
 * 只被 `VideoMetaManager.readResumeMs` / `saveProgress` 与 `PlayerActivity` 使用。
 */
object LocalVideoProgress {

    /** 小于等于这个位置不续播（毫秒），与旧逻辑 `resumeTarget > 5`（毫秒）保持一致。 */
    const val MIN_RESUME_MS: Long = 5_000L

    /** 距结尾小于等于这个值就算看完（毫秒）。 */
    const val END_TOLERANCE_MS: Long = 5_000L

    /**
     * 由已存进度 + 总时长算出真正该 seek 到的毫秒数；返回 0 表示从头播。
     *
     * 位置超过总时长（视频被换成更短的版本）同样返回 0——seek 到末尾只会立刻触发播放完成。
     */
    @JvmStatic
    fun resumeTargetMs(savedMs: Long, durationMs: Long): Long {
        if (savedMs <= MIN_RESUME_MS) return 0L
        if (durationMs > 0L && durationMs - savedMs <= END_TOLERANCE_MS) return 0L
        return savedMs
    }

    /**
     * 退出/周期落盘时真正要写进 meta 的毫秒数；返回 0 表示"清零"（看完或位置无效）。
     */
    @JvmStatic
    fun progressToSave(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0L) return 0L
        if (durationMs > 0L && durationMs - positionMs <= END_TOLERANCE_MS) return 0L
        return positionMs
    }
}
