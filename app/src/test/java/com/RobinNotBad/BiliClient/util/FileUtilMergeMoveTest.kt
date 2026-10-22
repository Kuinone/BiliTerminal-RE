package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 锁住 [FileUtil.mergeMoveDirectoryContents]：把「降级目录」里的缓存合并搬回「正式目录」。
 *
 * 这是降级机制的安全阀：用户后来授到了全盘权限（或把路径改回可写目录）时，缓存必须跟着回正式目录，
 * 否则列表里会突然少一批文件。要求是**只增不覆盖**——目标目录里已有的同名文件绝不能被来源覆盖或删掉。
 */
class FileUtilMergeMoveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `目标为空时把文件搬过去且来源清空`() {
        val from = tempFolder.newFolder("fallback")
        val to = tempFolder.newFolder("configured")
        File(from, "video.mp4").writeText("data")

        val moved = FileUtil.mergeMoveDirectoryContents(from, to)

        assertEquals(1, moved)
        assertEquals("data", File(to, "video.mp4").readText())
        assertTrue("来源目录不应残留已搬走的文件", (from.listFiles() ?: emptyArray()).isEmpty())
    }

    @Test
    fun `同名文件冲突时改名保留双方内容`() {
        val from = tempFolder.newFolder("fallback")
        val to = tempFolder.newFolder("configured")
        File(from, "video.mp4").writeText("new")
        File(to, "video.mp4").writeText("old")

        val moved = FileUtil.mergeMoveDirectoryContents(from, to)

        assertEquals(1, moved)
        // 目标里已有的成品绝不能被覆盖
        assertEquals("old", File(to, "video.mp4").readText())
        val renamed = File(to, "video (1).mp4")
        assertTrue("冲突文件应当改名后保留", renamed.isFile)
        assertEquals("new", renamed.readText())
    }

    @Test
    fun `目录递归合并而不是整目录替换`() {
        val from = tempFolder.newFolder("fallback")
        val to = tempFolder.newFolder("configured")
        File(from, "sub").mkdirs()
        File(to, "sub").mkdirs()
        File(from, "sub/b.txt").writeText("b")
        File(to, "sub/c.txt").writeText("c")
        // 两边都有目录、但文件各不相同：一个都不能丢
        File(from, "sub/d").mkdirs()
        File(from, "sub/d/e.txt").writeText("e")

        FileUtil.mergeMoveDirectoryContents(from, to)

        assertEquals("b", File(to, "sub/b.txt").readText())
        assertEquals("c", File(to, "sub/c.txt").readText())
        assertEquals("e", File(to, "sub/d/e.txt").readText())
    }

    @Test
    fun `不搬隐藏标记文件`() {
        val from = tempFolder.newFolder("fallback")
        val to = tempFolder.newFolder("configured")
        File(from, ".nomedia").createNewFile()
        File(from, "cache").mkdirs()

        val moved = FileUtil.mergeMoveDirectoryContents(from, to)

        // .nomedia 由 applyNoMedia 按开关自己维护，搬家时不能顺手把这个目录标记带过去
        assertEquals(1, moved)
        assertTrue(!File(to, ".nomedia").exists())
        assertTrue(File(to, "cache").isDirectory)
    }

    @Test
    fun `来源不存在或为空时是安全的空操作`() {
        val to = tempFolder.newFolder("configured")
        File(to, "keep.txt").writeText("keep")

        assertEquals(0, FileUtil.mergeMoveDirectoryContents(null, to))
        assertEquals(0, FileUtil.mergeMoveDirectoryContents(tempFolder.newFolder("empty"), to))
        assertEquals(0, FileUtil.mergeMoveDirectoryContents(to, to))
        assertEquals("keep", File(to, "keep.txt").readText())
    }
}
