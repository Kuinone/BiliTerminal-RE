package com.RobinNotBad.BiliClient.service

/**
 * 下载服务被系统以 START_STICKY 重启（onStartCommand 收到 null Intent）后的恢复决策。
 *
 * 背景（findings/robust.md P1-2）：进程被杀后系统会把 START_STICKY 的下载服务重新拉起，
 * 但 onStartCommand 收到的 Intent 是 null。修复前的实现直接 `return START_STICKY`：
 *
 * 1. 没有调 `startForeground` —— Android 8+ 对 `startForegroundService()` 拉起的服务有
 *    「5 秒内必须调用 startForeground」的硬约束，违反会抛 RemoteServiceException 并杀进程
 *    （PlaybackService 里记录的 26.10.10 线上崩溃就是同类问题）；
 * 2. 没有把下载队列续跑起来 —— 即使进程被系统重新建起来，下载也永远停在那里。
 *
 * 队列本身是**持久化**的：任务存在 SQLite（`download.db` 的 `download` 表，见 DownloadSqlHelper），
 * 每行 state 取 none/downloading/paused/error；DownloadRepository.getAll() 在表空时返回 null、
 * 查询异常时返回空列表。所以重启后能不能续跑只取决于「还有没有 state=none 的可恢复任务」。
 * 另一个坑是 `DownloadService.started` 是进程级内存标志：新进程里它是 false，
 * 系统重启路径没有人调用 start()，不显式置位的话调度循环 `while (started)` 会立刻退出。
 *
 * 这里只放纯决策，不碰 Android API，以便 JVM 单测覆盖决策矩阵。
 */
internal object DownloadServiceRecovery {

    /** 下载表 state 列里「排队中、等待调度器拾取」的值（与 DownloadRepository.getFirst 的查询一致）。 */
    const val STATE_NONE = "none"

    /** 一次 onStartCommand 之后应该走哪条路径。 */
    enum class Action {
        /** 正常 Intent 启动（用户点击下载/继续）：保持既有批次流程，不按队列数量拦截。 */
        START,

        /** START_STICKY 重启且队列里还有可恢复任务：重新前台化并续跑。 */
        RESUME,

        /** START_STICKY 重启但队列已无可恢复任务：兑现完 5 秒义务后立即 stopSelf，避免空转。 */
        STOP
    }

    /**
     * 决定本次 onStartCommand 的走向。
     *
     * @param intentIsNull onStartCommand 的 Intent 是否为 null（true = 系统 START_STICKY 重启）
     * @param pendingCount 重启场景下 state=none 的可恢复任务数；调用方应在
     *                     recoverStuckSections() 之后统计（它会把遗留的 "downloading" 重置为 "none"）
     * @return 见 [Action]；非重启场景一律 [Action.START]（队列是否为空交给既有批次流程自行收尾）
     */
    fun decide(intentIsNull: Boolean, pendingCount: Int): Action = when {
        !intentIsNull -> Action.START
        pendingCount > 0 -> Action.RESUME
        else -> Action.STOP
    }

    /**
     * 统计可恢复任务数。
     *
     * state 为 paused（用户主动暂停）或 error（runDownloadSection 判定的终态）的不算：
     * 前者必须等用户点「继续」，后者已被判定为失败，都不应被冷启动自动重跑。
     *
     * @param states download 表各行的 state；null（表空/查询失败）按 0 处理
     */
    fun countResumable(states: List<String?>?): Int =
        states?.count { it == STATE_NONE } ?: 0
}
