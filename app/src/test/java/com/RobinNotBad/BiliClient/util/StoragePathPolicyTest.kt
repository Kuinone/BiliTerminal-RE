package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 [StoragePathPolicy] 的三条决策，都能在纯 JVM 里跑（该对象刻意不引用 Android API）。
 *
 * 背景（issue #42 / Wear OS）：默认缓存目录必须「任何版本都免权限」，否则手表 ROM 没有
 * 「所有文件访问」设置页时下载会被永久挡死；同时老用户升级后缓存不能突然变空。
 */
class StoragePathPolicyTest {

    private val mediaDir = "/storage/emulated/0/Android/media/com.RobinNotBad.BiliClient/"
    private val appExternalDir = "/storage/emulated/0/Android/data/com.RobinNotBad.BiliClient/files/video"
    private val internalDir = "/data/user/0/com.RobinNotBad.BiliClient/files/video"

    // ==================== defaultVideoDir ====================

    @Test
    fun `API19 不用老默认目录而用应用专属外部目录`() {
        // Android 4.4 上 Android/media 仍是公共存储语义，直接写要 WRITE_EXTERNAL_STORAGE，
        // 而下载链路不想再为默认目录要权限，所以退到 getExternalFilesDir("video")。
        assertEquals(appExternalDir, StoragePathPolicy.defaultVideoDir(19, mediaDir, appExternalDir, internalDir))
    }

    @Test
    fun `API20 与 API19 同档`() {
        assertEquals(appExternalDir, StoragePathPolicy.defaultVideoDir(20, mediaDir, appExternalDir, internalDir))
    }

    @Test
    fun `API21 起回到老默认目录`() {
        assertEquals(mediaDir, StoragePathPolicy.defaultVideoDir(21, mediaDir, appExternalDir, internalDir))
        assertEquals(mediaDir, StoragePathPolicy.defaultVideoDir(33, mediaDir, appExternalDir, internalDir))
    }

    @Test
    fun `API21 但老默认目录为空时退应用专属外部目录`() {
        assertEquals(appExternalDir, StoragePathPolicy.defaultVideoDir(33, null, appExternalDir, internalDir))
    }

    @Test
    fun `应用专属外部目录和内部目录都取不到时兜底返回老目录路径`() {
        assertEquals(mediaDir, StoragePathPolicy.defaultVideoDir(33, mediaDir, null, null))
    }

    @Test
    fun `全部为空时返回空串而不是抛异常`() {
        assertEquals("", StoragePathPolicy.defaultVideoDir(33, null, null, null))
    }

    // ==================== chooseVideoDir ====================

    @Test
    fun `用户配置过路径时优先用户配置`() {
        // 哪怕它与默认目录相同：语义是"用户自己定的"，降级/迁移都不能改它。
        assertEquals("/sdcard/MyCache/", StoragePathPolicy.chooseVideoDir("/sdcard/MyCache/", mediaDir, true, mediaDir))
    }

    @Test
    fun `配置为空白时视为没配置`() {
        assertEquals(mediaDir, StoragePathPolicy.chooseVideoDir("   ", mediaDir, true, mediaDir))
        assertEquals("默认", StoragePathPolicy.chooseVideoDir(null, null, false, "默认"))
        assertEquals("默认", StoragePathPolicy.chooseVideoDir("", null, false, "默认"))
    }

    @Test
    fun `没配置但老默认目录还有缓存时继续用老目录`() {
        // 老用户从老版本升级上来的情况：缓存不能因为换默认目录就"消失"。
        assertEquals(mediaDir, StoragePathPolicy.chooseVideoDir(null, mediaDir, true, appExternalDir))
    }

    @Test
    fun `没配置且老默认目录没有内容时用新的默认目录`() {
        assertEquals(appExternalDir, StoragePathPolicy.chooseVideoDir(null, mediaDir, false, appExternalDir))
    }

    // ==================== hasRealContent ====================

    @Test
    fun `空目录与只有隐藏标记文件的目录都算没有内容`() {
        assertFalse(StoragePathPolicy.hasRealContent(null))
        assertFalse(StoragePathPolicy.hasRealContent(emptyList()))
        // .nomedia 是本应用自己建的，.DOWNLOADING/.write_probe 是断点标记，
        // 都不能把老用户永远钉在旧目录上。
        assertFalse(StoragePathPolicy.hasRealContent(listOf(".nomedia")))
        assertFalse(StoragePathPolicy.hasRealContent(listOf(".nomedia", ".DOWNLOADING", ".write_probe")))
    }

    @Test
    fun `有真实缓存条目的目录算有内容`() {
        assertTrue(StoragePathPolicy.hasRealContent(listOf(".nomedia", "某视频")))
        assertTrue(StoragePathPolicy.hasRealContent(listOf("video.mp4")))
    }
}
