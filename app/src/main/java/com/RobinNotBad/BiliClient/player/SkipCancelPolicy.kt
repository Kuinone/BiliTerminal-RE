package com.RobinNotBad.BiliClient.player

/**
 * 「一次跳转是否应当**取消**这条自动跳过」的纯判定（无 Android 依赖，可直接 JVM 单测）。
 *
 * **为什么需要这个文件**（26.10.22 修「乱跳」）：`seekToVideo()` 是所有跳转的唯一入口，
 * 而它在 `PlayerActivity` 里**无法区分调用者**——用户拖进度条、按键、滑动、点看点，以及
 * 自动跳过自己，全部走它。原先的实现是「谁走进来都把落点所在的片段记成已处理」（`onUserSeekTo`），
 * 于是两路互相污染：
 *
 * - 自动跳过的落点 `toSec` 虽然在区间之外，但与用户操作**撞在同一帧/相邻帧**时会被记成已处理，
 *   真正该跳的片段被吃掉；
 * - 反过来，用户只是拖动过程中的中间帧可能落进片段，把用户还没决定要看的片段误标。
 *
 * 现在由调用点把自己**声明**的 `userInitiated` 传进来，本文件只负责回答「这一次跳转算不算反悔」。
 * 判定与取样分离：真正写 `skipHandled` / 起冷静期的是播放页。
 */
object SkipCancelPolicy {

    /**
     * 用户主动跳转落在某片段内时，是否应当把该片段记为「已处理」（即：这次跳转视为反悔）。
     *
     * 语义仍然是「用户主动跳进片头 = 他想看片头，不要再弹走他」，与 [ViewPointSkip.keyForManualSeek]
     * 一致；区别只在于**自动跳过自己调用时一律不算**，避免两路互相污染。
     *
     * @param userInitiated 是否由用户手势/按键发起（自动跳过、撤回按钮之外的自动流程传 false）
     * @param segmentKey 落点所在片段的 key；落点不在任何片段内传 null
     * @param handled 本集已经处理过的片段 key
     * @return 需要标记的 key；无需标记返回 null
     */
    fun cancelKeyFor(userInitiated: Boolean, segmentKey: String?, handled: Set<String>): String? {
        if (!userInitiated) return null
        if (segmentKey == null) return null
        if (handled.contains(segmentKey)) return null
        return segmentKey
    }

    /**
     * 新一批片段与该集既有的 `skipSegments` 是否**代表同一集的内容**。
     *
     * **这是「乱跳」的第二个来源**：`loadViewPoints()` 在一次播放里可以被调用多次
     * （启动、换 P、投屏恢复等三处调用点），而它原先**无条件** `skipHandled.clear()`。
     * 只要那次重新拉取发生在用户已经越过片头之后，响应一到达就把「片头已处理」抹掉 →
     * 下一轮定时器重新命中 → 再跳一次。用户看到的就是「开播十几秒后自己往前跳」，
     * 而且每多拉一次就多跳一次。
     *
     * 判据刻意用「片段表是否完全相同」而不是简单的 `isEmpty` 判断：换集/换 P 后片段通常会变，
     * 变了就应该重置；只有**完全同一批片段**（迟到的重复响应、投屏恢复后的重拉）才保留 handled。
     *
     * @param old 该集原有的片段表（首次加载时为空表）
     * @param new 这次拉取算出的片段表
     */
    fun segmentsChanged(old: List<ViewPointSkip.Segment>, new: List<ViewPointSkip.Segment>): Boolean {
        if (old.size != new.size) return true
        // 两个列表都按起点排好序（buildSegments 保证），逐项比对即可
        for (i in old.indices) {
            if (old[i] != new[i]) return true
        }
        return false
    }
}
