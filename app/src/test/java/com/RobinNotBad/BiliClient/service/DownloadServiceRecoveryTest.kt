package com.RobinNotBad.BiliClient.service

import com.RobinNotBad.BiliClient.service.DownloadServiceRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * START_STICKY 重启决策的 JVM 单测（findings/robust.md P1-2）。
 *
 * 现场：下载服务被系统杀掉后，START_STICKY 会把它重新拉起，此时 onStartCommand 的
 * Intent 为 null。修复前这条路径直接 return，既没兑现「5 秒内 startForeground」的前台义务，
 * 也没续跑数据库里的下载队列。这里把「重启 + 有/无任务」「正常启动」「批次已完成」四种
 * 组合锁死，防止以后有人把 null Intent 分支改回无条件 return。
 */
class DownloadServiceRecoveryTest {

    // ---------- decide：重启 + 有任务 → 续跑 ----------

    @Test
    fun decide_重启且有可恢复任务时续跑() {
        assertEquals(Action.RESUME, DownloadServiceRecovery.decide(true, 1))
        assertEquals(Action.RESUME, DownloadServiceRecovery.decide(true, 7))
    }

    // ---------- decide：重启 + 无任务 → 停止 ----------

    @Test
    fun decide_重启且队列为空时停止() {
        assertEquals(Action.STOP, DownloadServiceRecovery.decide(true, 0))
        // 负数是异常输入：宁可当空队列收摊，也不能当成"还有任务要下"去空转
        assertEquals(Action.STOP, DownloadServiceRecovery.decide(true, -1))
    }

    @Test
    fun decide_批次正常完成后被重启_应停止() {
        // 对应现场：用户下完一批、服务在 stopSelf 前被杀，系统按 START_STICKY 拉起时 intent=null。
        // 此时 DB 里已没有 state=none 的任务，必须直接收摊，不能为"空批次"前台常驻。
        assertEquals(Action.STOP, DownloadServiceRecovery.decide(true, 0))
    }

    // ---------- decide：正常 Intent 启动 → 不拦截 ----------

    @Test
    fun decide_正常Intent启动时不按队列数量拦截() {
        // 正常启动（用户点下载/继续）与重启不同：队列刚插入、或只剩 paused 项都是合法状态，
        // 交给既有批次流程自己收尾，不能在这里提前 STOP。
        assertEquals(Action.START, DownloadServiceRecovery.decide(false, 0))
        assertEquals(Action.START, DownloadServiceRecovery.decide(false, 5))
    }

    // ---------- countResumable：只有 none 算可恢复 ----------

    @Test
    fun countResumable_只统计none() {
        assertEquals(0, DownloadServiceRecovery.countResumable(null))
        assertEquals(0, DownloadServiceRecovery.countResumable(emptyList()))
        assertEquals(0, DownloadServiceRecovery.countResumable(listOf("downloading", "paused", "error")))
        // paused=用户主动暂停、error=终态，都不能被冷启动自动重跑；null 行按不可恢复处理
        assertEquals(2, DownloadServiceRecovery.countResumable(listOf("none", "downloading", "none", null)))
    }

    @Test
    fun countResumable_遗留downloading需先被recoverStuckSections重置() {
        // 进程被杀时正在下的任务在 DB 里是 "downloading"。recoverStuckSections() 会把它改回
        // "none"，统计必须发生在它之后，否则这批任务会被漏掉、重启后不续跑。
        val beforeRecover = listOf("downloading")
        assertEquals(0, DownloadServiceRecovery.countResumable(beforeRecover))

        val afterRecover = beforeRecover.map { if (it == "downloading") "none" else it }
        assertEquals(1, DownloadServiceRecovery.countResumable(afterRecover))
    }

    // ---------- 常量：与调度器查询口径一致 ----------

    @Test
    fun stateNone_与调度器查询用的字面量一致() {
        // DownloadRepository.getFirst() 用 state="none" 取下一个任务；
        // state 列取值散落在多个文件里，这里锁死常量，防止改一处漏一处。
        assertEquals("none", DownloadServiceRecovery.STATE_NONE)
    }
}
