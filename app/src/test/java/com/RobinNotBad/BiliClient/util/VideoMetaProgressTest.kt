package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.model.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 锁住本地续播位置在 `.video_meta.json` 里的**持久化格式与合并写**：
 *
 * - 老版本写下的 meta（没有 progressMs/durationMs）读回来必须是 0，不能崩；
 * - 写进度不能把 title / aid / cid / 画质冲掉——下载链路（重下载、换清晰度）
 *   会整份重写 meta，冲掉就等于"每次重新下载都丢失续播位置"；
 * - 写进去的位置必须能被 [VideoMetaManager.readResumeMs] 原样读回，播到结尾要落成 0。
 *
 * 这些用例能跑在纯 JVM 里，是因为 `VideoMeta` 只用 `org.json`（`app/build.gradle` 里
 * 有真的 `testImplementation 'org.json:json'`）与 `File`，不碰 Context。
 */
class VideoMetaProgressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun videoDir(): File = tempFolder.newFolder("123456")

    private fun metaFile(dir: File): File = File(dir, ".video_meta.json")

    @Test
    fun `老格式 meta 没有进度字段时读回 0`() {
        val dir = videoDir()
        metaFile(dir).writeText(
            """{"folderName":"","aid":1,"cid":2,"qn":64,"title":"老视频","downloadType":"video"}"""
        )

        val meta = VideoMetaManager.readMeta(dir)
        assertEquals("老视频", meta.title)
        assertEquals(0L, meta.progressMs)
        assertEquals(0L, meta.durationMs)
        assertEquals(0L, VideoMetaManager.readResumeMs(dir))
    }

    @Test
    fun `进度写进去能原样读回`() {
        val dir = videoDir()

        VideoMetaManager.saveProgress(dir, 90_000L, 600_000L)

        assertEquals(90_000L, VideoMetaManager.readResumeMs(dir))
        val meta = VideoMetaManager.readMeta(dir)
        assertEquals(90_000L, meta.progressMs)
        assertEquals(600_000L, meta.durationMs)
    }

    @Test
    fun `落盘进度不冲掉标题画质与画质列表`() {
        val dir = videoDir()
        val origin = VideoMeta()
        origin.title = "某个视频"
        origin.aid = 111L
        origin.cid = 222L
        origin.qn = 80
        origin.qnStrList = arrayOf("1080P", "720P")
        origin.qnValueList = intArrayOf(80, 64)
        VideoMetaManager.saveMeta(dir, origin)

        VideoMetaManager.saveProgress(dir, 120_000L, 600_000L)

        val meta = VideoMetaManager.readMeta(dir)
        assertEquals("某个视频", meta.title)
        assertEquals(111L, meta.aid)
        assertEquals(222L, meta.cid)
        assertEquals(80, meta.qn)
        assertEquals(listOf("1080P", "720P"), meta.qnStrList?.toList())
        assertEquals(listOf(80, 64), meta.qnValueList?.toList())
        assertEquals(120_000L, meta.progressMs)
    }

    @Test
    fun `播到结尾落盘为从头播`() {
        val dir = videoDir()
        VideoMetaManager.saveProgress(dir, 120_000L, 600_000L)

        VideoMetaManager.saveProgress(dir, 598_000L, 600_000L)

        assertEquals(0L, VideoMetaManager.readResumeMs(dir))
        val meta = VideoMetaManager.readMeta(dir)
        assertEquals(0L, meta.progressMs)
        assertEquals(0L, meta.durationMs)
    }

    @Test
    fun `时长未知时也能续播`() {
        val dir = videoDir()

        VideoMetaManager.saveProgress(dir, 90_000L, 0L)

        assertEquals(90_000L, VideoMetaManager.readResumeMs(dir))
    }

    @Test
    fun `目录不存在时读取返回 0 不抛异常`() {
        val missing = File(tempFolder.root, "not-exists")

        assertEquals(0L, VideoMetaManager.readResumeMs(missing))
    }
}
