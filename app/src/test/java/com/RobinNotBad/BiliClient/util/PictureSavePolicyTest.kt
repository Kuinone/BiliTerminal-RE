package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片保存目录策略（安全审计 P2-2）的纯规则测试。
 *
 * 为什么钉这几条：默认目录决定「要不要申请所有文件访问权限」——分档边界写错一位，
 * Android 10/11 手机上就会静默退回到公共目录裸写，于是下载全部失败（或反过来，
 * 老设备明明能写公共相册却被塞进应用私有目录、用户再也找不到图）。
 */
class PictureSavePolicyTest {

    @Test
    fun usesAppPrivateDefault_splitAtApi29() {
        assertFalse("API 28 及以下：公共 Pictures 仍可免 MANAGE 写入", PictureSavePolicy.usesAppPrivateDefault(28))
        assertFalse(PictureSavePolicy.usesAppPrivateDefault(19))
        assertTrue("Android 10 起分区存储生效", PictureSavePolicy.usesAppPrivateDefault(29))
        assertTrue(PictureSavePolicy.usesAppPrivateDefault(30))
        assertTrue(PictureSavePolicy.usesAppPrivateDefault(34))
    }

    @Test
    fun cleanConfiguredPath_blankMeansUnconfigured() {
        assertNull("null 应视为未配置", PictureSavePolicy.cleanConfiguredPath(null))
        assertNull("空串应视为未配置", PictureSavePolicy.cleanConfiguredPath(""))
        assertNull("全空白应视为未配置", PictureSavePolicy.cleanConfiguredPath("   \t\n "))
    }

    @Test
    fun cleanConfiguredPath_trimsRealPath() {
        assertEquals("/storage/emulated/0/Pictures/custom", PictureSavePolicy.cleanConfiguredPath("  /storage/emulated/0/Pictures/custom  "))
        assertEquals("downloads", PictureSavePolicy.cleanConfiguredPath("\tdownloads\n"))
    }

    @Test
    fun publicSubdir_isStable() {
        // 这个目录名会出现在相册与 toast 里，改名等于改变用户可见行为，需要单独决定
        assertEquals("哔哩终端", PictureSavePolicy.PUBLIC_PICTURE_SUBDIR)
    }
}
